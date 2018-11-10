package io.flashbook.flashbot.sources

import java.io.File
import java.net.URI

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import io.flashbook.flashbot.core.{DataSource, _}
import io.flashbook.flashbot.core.Order.{Buy, OrderType, Sell, Side}
import io.flashbook.flashbot.core.OrderBook.{OrderBookMD, SnapshotOrder}
import io.flashbook.flashbot.util
import io.flashbook.flashbot.engine.OrderBookProvider.Subscribe
import io.flashbook.flashbot.engine.TimeLog.TimeLog
import io.circe.Json
import io.circe.generic.auto._
import io.flashbook.flashbot.core.DataSource.{DepthBook, FullBook, Trades, parseBuiltInDataType}
import io.flashbook.flashbot.engine.{OrderBookProvider, TimeLog}
import net.openhft.chronicle.queue.TailerDirection
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake

import scala.collection.immutable.{Queue, SortedSet}
import scala.concurrent.Future

class CoinbaseMarketDataSource extends DataSource {

  // Save an order book snapshot every 1M events per product
  val snapshotInterval = 1000000

  val NAME = "coinbase"

  override def ingest(dataDir: String,
                      topics: Map[String, Json],
                      dataTypes: Map[String, DataSource.DataTypeConfig])
                     (implicit system: ActorSystem,
                      mat: ActorMaterializer): Unit = {

    val dts = dataTypes.map { case (k, v) => (parseBuiltInDataType(k).get, v) }
    val fullStream = Source
      .actorRef[OrderBookMD[APIOrderEvent]](Int.MaxValue, OverflowStrategy.fail)
      .groupBy(1000, _.product)
      .scan[(Option[(TimeLog[APIOrderEvent], TimeLog[SnapshotItem],
                      TimeLog[TradeMD])],
            Long, Option[OrderBookMD[APIOrderEvent]])]((None, -1, None)) {
        case ((queueOpt, count, prev), book) =>
          (Some(queueOpt.getOrElse((
            timeLog[APIOrderEvent](dataDir, book.product, "book/events"),
            timeLog[SnapshotItem](dataDir, book.product, "book/snapshots"),
            timeLog[TradeMD](dataDir, book.product, book.dataType)))),
          count + 1,
          Some(book))
      }
      .to(Sink.foreach {
        case (Some((eventsQueue, snapshotsQueue, tradesQueue)), count,
                Some(md @ OrderBookMD(_, _, _, Some(rawEvent: APIOrderEvent), _))) =>

          // Persist the full order book stream
          if (dts.isDefinedAt(FullBook)) {
            // Save events to disk
            eventsQueue.enqueue(rawEvent)
            // And periodic snapshots too
            if (count % snapshotInterval == 0) {
              var itemCount = 1
              val snapshotOrders = md.getSnapshot
              snapshotOrders.foreach { order =>
                snapshotsQueue.enqueue(SnapshotItem(order, md.micros, itemCount,
                  snapshotOrders.size))
                itemCount = itemCount + 1
              }
            }
          }

          // Persist trades separately. Derived from the same order book stream.
          if (dts.isDefinedAt(Trades)) {
            rawEvent.toOrderEvent match {
              case OrderMatch(tradeId, product, time, size, price, side, _, _) =>
                tradesQueue.enqueue(TradeMD(NAME, product.toString,
                  Trade(tradeId.toString, time, price, size, side match {
                    case Buy => Sell
                    case Sell => Buy
                  })))
              case _ =>
            }
          }
      })
      .run
    system.actorOf(Props(new LiveGDAXMarketData(fullStream ! _))) !
      Subscribe(topics.keySet.map(util.parseProductId))
  }

  override def stream(dataDir: String, topic: String,
                      dataType: String, timeRange: TimeRange): Iterator[MarketData] = {

    parseBuiltInDataType(dataType) match {
      case Some(x) => (x, timeRange) match {
        case (FullBook, TimeRange(from, to)) =>

          val snapshotQueue =
            timeLog[SnapshotItem](dataDir, util.parseProductId(topic), "book/snapshots")

          // Read the snapshots queue backwards until we can build up the latest snapshot.
          var snapOrders: Option[Queue[SnapshotOrder]] = None
          for (item <- snapshotQueue
              .scanBackwards(_.index != 1 || snapOrders.isEmpty)(snapshotQueue.close)) {
            (snapOrders, item) match {
              case (None, SnapshotItem(order, time, index, total))
                if index == total && time >= from && to > time =>
                snapOrders = Some(Queue.empty.enqueue(order))
              case (Some(_), SnapshotItem(order, _, index, _)) if index == 1 =>
                snapOrders = Some(snapOrders.get.enqueue(order))
              case (Some(snapshotOrders), SnapshotItem(order, _, _, _)) =>
                snapOrders = Some(snapshotOrders.enqueue(order))
              case _ =>
            }
          }

          if (snapOrders.isDefined) {
            val eventsQueue =
              timeLog[APIOrderEvent](dataDir, util.parseProductId(topic), "book/events")
            val seq = snapOrders.get.head.seq
            var state = OrderBookMD[APIOrderEvent](NAME, topic).addSnapshot(seq, snapOrders.get)

            for (event <- eventsQueue.scan(seq + 1, _.seq, { event =>
              val prevState = state
              val foundNextEvent = event.seq == prevState.seq + 1
              if (foundNextEvent) {
                state = prevState.addEvent(event)
              }
              foundNextEvent
            })(eventsQueue.close))
              yield state

          } else {
            List().iterator
          }

        case (Trades, TimeRange(from, to)) =>
          val tradesQueue =
            timeLog[TradeMD](dataDir, util.parseProductId(topic), "trades")
          tradesQueue.scan(from, _.micros, md => md.micros < to)(tradesQueue.close)

        case (DepthBook(_), _) =>
          throw new RuntimeException(s"Coinbase order book aggregations not yet implemented")
      }
      case None =>
        throw new RuntimeException(s"Unknown data type: $dataType")
    }
  }

  private def timeLog[T <: Timestamped](dataDir: String, product: Pair, name: String) =
    TimeLog[T](new File(s"$dataDir/$NAME/$product/$name"))

  /**
    * Additional metadata attached to each SnapshotOrder that helps us read snapshots from the
    * queue without maintaining a separate index.
    */
  case class SnapshotItem(order: SnapshotOrder, micros: Long, index: Int, total: Int)
    extends Timestamped


  // Has to check that sequence number are actually sequential.
  // Use in memory buffer to handle out-of-order messages.
  class LiveGDAXMarketData(updateFn: OrderBookMD[APIOrderEvent] => Unit)
                          (implicit system: ActorSystem,
                           mat: ActorMaterializer)
    extends OrderBookProvider[APIOrderEvent](NAME, updateFn) {

    import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
    import io.circe.generic.auto._

    var ws: Option[WebSocketClient] = None
    var buffers = Map.empty[Pair, (Long, SortedSet[APIOrderEvent])]
    var subscribed = false

    case class OrderBookResponse(sequence: Long,
                                 bids: List[(String, String, String)],
                                 asks: List[(String, String, String)])

    override def getSnapshot(p: Pair,
                             first: APIOrderEvent): Future[(Long, Seq[SnapshotOrder])] = {
      Http().singleRequest(HttpRequest(
        uri = s"https://api.gdax.com/products/$p/book?level=3"))
        .flatMap(r => Unmarshal(r.entity)
          .to[OrderBookResponse]
          .map(x => {
            val bids = x.bids.map(o => SnapshotOrder(p.toString,
              x.sequence, bid = true, o._3, o._1.toDouble, o._2.toDouble))
            val asks = x.asks.map(o => SnapshotOrder(p.toString,
              x.sequence, bid = false, o._3, o._1.toDouble, o._2.toDouble))
            (x.sequence, bids ++ asks)
          })
        )
      }

    override def ingest(ps: Set[Pair], onEvent: APIOrderEvent => Unit): Unit = {
      // This is where we process every event coming in from the wire that occurs after we get
      // the initial "subscriptions" event. They are not guaranteed to be ordered correctly.
      def ingestEvent(ev: UnparsedAPIOrderEvent): Unit = {
        val p: Pair = util.parseProductId(ev.product_id)

        // If this is the first event for this product, then initialize the buffer.
        if (!buffers.isDefinedAt(p)) {
          buffers = buffers + (p -> (0, SortedSet.empty[APIOrderEvent]))
        }

        // Parse event into an APIOrderEvent and add it to buffer.
        buffers = buffers + (p -> (buffers(p)._1, buffers(p)._2 + ev.parse))

        // Now, replay the buffer while the head is sequential to the running seq number.
        while (buffers(p)._2.nonEmpty &&
          (buffers(p)._1 == buffers(p)._2.head.seq - 1 || buffers(p)._1 == 0)) {
          val head = buffers(p)._2.head
          onEvent(head)
          buffers = buffers + (p -> (head.seq, buffers(p)._2 - head))
        }
      }

      // Open a WebSocket
      ws = Some(new WebSocketClient(new URI("wss://ws-feed.gdax.com")) {
        override def onOpen(handshakedata: ServerHandshake): Unit = {}

        override def onMessage(message: String): Unit = {
          util.json.parseJson[UnparsedAPIOrderEvent](message) match {
            case Right(ev) =>
              if (subscribed)
                ingestEvent(ev)

            case Left(err) =>
              // HACK HACK HACK DON'T JUDGE
              // A parsing error is interpreted as the only type of message that we don't know how
              // to parse: A msg of type "subscriptions". Now, for some reason, there are usually
              // some skipped messages between events received before and after "subscriptions".
              // Therefore we choose to ignore all messages before "subscriptions".
              if (subscribed)
              // We only expect one parse error, so crash if there are more.
                crash(err)
              else
                subscribed = true
          }
        }

        override def onClose(code: Int, reason: String, remote: Boolean): Unit = {}

        override def onError(ex: Exception): Unit = throw ex
      })

      val s = s"""
           |{
           | "type": "subscribe",
           | "product_ids": [${ps.map(p => s""""${p.toString.toUpperCase}"""").mkString(", ")}],
           | "channels": ["full"]
           |}
        """.stripMargin

      // Subscribe on the websocket
      ws.get.send(s)

    }
  }

  // How we store API events on disk
  case class APIOrderEvent(`type`: String,
                            product_id: String,
                            seq: Long,
                            micros: Long,
                            size: Option[Double],
                            price: Option[Double],
                            order_id: Option[String],
                            side: Option[String],
                            reason: Option[String],
                            order_type: Option[String],
                            remaining_size: Option[Double],
                            funds: Option[Double],
                            trade_id: Option[Long],
                            maker_order_id: Option[String],
                            taker_order_id: Option[String],
                            taker_user_id: Option[String],
                            user_id: Option[String],
                            taker_profile_id: Option[String],
                            profile_id: Option[String],
                            new_size: Option[Double],
                            old_size: Option[Double],
                            new_funds: Option[Double],
                            old_funds: Option[Double],
                            last_size: Option[Double],
                            best_bid: Option[Double],
                            best_ask: Option[Double],
                            client_oid: Option[String])
      extends RawOrderEvent with Ordered[APIOrderEvent] {

    override def compare(that: APIOrderEvent): Int = {
      if (seq == that.seq) 0
      else if (seq > that.seq) 1
      else -1
    }

    def toOrderEvent: OrderEvent = {
      `type` match {
        case "open" =>
          OrderOpen(order_id.get, util.parseProductId(product_id), price.get, remaining_size.get,
            Side.parseSide(side.get))
        case "done" =>
          OrderDone(order_id.get, util.parseProductId(product_id), Side.parseSide(side.get),
            DoneReason.parse(reason.get), price, remaining_size)
        case "change" =>
          OrderChange(order_id.get, util.parseProductId(product_id), price, new_size.get)
        case "match" =>
          OrderMatch(trade_id.get, util.parseProductId(product_id), micros, size.get, price.get,
            Side.parseSide(side.get), maker_order_id.get, taker_order_id.get)
        case "received" =>
          OrderReceived(order_id.get, util.parseProductId(product_id), client_oid,
            OrderType.parseOrderType(order_type.get))
      }
    }
  }

  // How we receive order events from the API. Fields are strings for some reason.
  case class UnparsedAPIOrderEvent(`type`: String,
                                   product_id: String,
                                   sequence: Option[Long],
                                   time: Option[String],
                                   size: Option[String],
                                   price: Option[String],
                                   order_id: Option[String],
                                   side: Option[String],
                                   reason: Option[String],
                                   order_type: Option[String],
                                   remaining_size: Option[String],
                                   funds: Option[String],
                                   trade_id: Option[Long],
                                   maker_order_id: Option[String],
                                   taker_order_id: Option[String],
                                   taker_user_id: Option[String],
                                   user_id: Option[String],
                                   taker_profile_id: Option[String],
                                   profile_id: Option[String],
                                   new_size: Option[String],
                                   old_size: Option[String],
                                   new_funds: Option[String],
                                   old_funds: Option[String],
                                   last_size: Option[String],
                                   best_bid: Option[String],
                                   best_ask: Option[String],
                                   client_oid: Option[String]) {

    def parse: APIOrderEvent = APIOrderEvent(`type`, product_id, sequence.get,
      time.map(util.time.ISO8601ToMicros).get, size.map(_.toDouble), price.map(_.toDouble), order_id,
      side, reason, order_type, remaining_size.map(_.toDouble), funds.map(_.toDouble), trade_id,
      maker_order_id, taker_order_id, taker_user_id, user_id, taker_profile_id, profile_id,
      new_size.map(_.toDouble), old_size.map(_.toDouble), new_funds.map(_.toDouble),
      old_funds.map(_.toDouble), last_size.map(_.toDouble), best_bid.map(_.toDouble),
      best_ask.map(_.toDouble), client_oid)
  }
}
