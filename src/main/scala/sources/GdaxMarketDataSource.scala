package sources

import java.io.File

import akka.NotUsed
import akka.actor.Status.Success
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import com.github.andyglow.websocket.{Websocket, WebsocketClient}
import core._
import core.Order.{OrderType, Side}
import core.OrderBook.{OrderBookMD, SnapshotOrder}
import core.Utils.{ISO8601ToMicros, parseJson, parseProductId}
import data.OrderBookProvider.Subscribe
import data.PersistentQueue.{PersistentQueue, QueueBound}
import data.{OrderBookProvider, PersistentQueue}
import io.circe.Json
import core.DataSource.{DepthBook, FullBook, Trades, parseBuiltInDataType}
import net.openhft.chronicle.queue.TailerDirection

import scala.collection.immutable.{Queue, SortedSet}
import scala.concurrent.Future

class GdaxMarketDataSource extends DataSource {

  // Save an order book snapshot every 1M events per product
  val snapshotInterval = 1000000

  override def ingest(dataDir: String,
                      topics: Map[String, Json],
                      dataTypes: Map[String, DataSource.DataTypeConfig])
                     (implicit system: ActorSystem,
                      mat: ActorMaterializer): Unit = {

    val fullStream = Source
      .actorRef[OrderBookMD[APIOrderEvent]](Int.MaxValue, OverflowStrategy.fail)
      .groupBy(1000, _.product)
      .scan[(Option[(PersistentQueue[APIOrderEvent], PersistentQueue[SnapshotItem],
                      PersistentQueue[TradeMD])],
            Long, Option[OrderBookMD[APIOrderEvent]])]((None, -1, None)) {
        case ((queueOpt, count, prev), book) =>
          (Some(queueOpt.getOrElse((
            queue[APIOrderEvent](dataDir, book.product, "book/events"),
            queue[SnapshotItem](dataDir, book.product, "book/snapshots"),
            queue[TradeMD](dataDir, book.product, "trades")))),
          count + 1,
          Some(book))
      }
      .to(Sink.foreach {
        case (Some((eventsQueue, snapshotsQueue, tradesQueue)), count,
                Some(md @ OrderBookMD(_, _, _, Some(rawEvent), _))) =>

          // Persist the full order book stream
          if (dataTypes.isDefinedAt("book")) {
            // Save events to disk
            eventsQueue.enqueue(rawEvent)
            // And periodic snapshots too
            if (count % snapshotInterval == 0) {
              var itemCount = 1
              val snapshotOrders = md.getSnapshot
              snapshotOrders.foreach {
                order =>
                  snapshotsQueue.enqueue(SnapshotItem(order, md.time, itemCount,
                    snapshotOrders.size))
                  itemCount = itemCount + 1
              }
            }
          }

          // Persist trades separately. Derived from the same order book stream.
          if (dataTypes.isDefinedAt("trades")) {
            rawEvent.toOrderEvent match {
              case Match(tradeId, product, time, size, price, _, _, _) =>
                tradesQueue.enqueue(TradeMD("gdax", product.toString,
                  Trade(tradeId.toString, time, price, size)))
              case _ =>
            }
          }
      })
      .run
    system.actorOf(Props(new LiveGDAXMarketData(fullStream ! _))) !
      Subscribe(topics.keySet.map(parseProductId))
  }

  override def stream(sink: Sink[MarketData, NotUsed], dataDir: String, topic: String,
                      dataType: String, timeRange: TimeRange): Unit = {
    val ref = Source.actorRef(Int.MaxValue, OverflowStrategy.fail).to(sink).run

    // TODO: Full table scans :( Remove them using Chronicle Queue indices
    parseBuiltInDataType(dataType) match {
      case Some(x) => (x, timeRange) match {

        case (FullBook, TimeRange(from, to)) =>
          val eventsQueue: PersistentQueue[APIOrderEvent] =
            queue(dataDir, parseProductId(topic), "book/events")
          val snapshotQueue: PersistentQueue[SnapshotItem] =
            queue(dataDir, parseProductId(topic), "book/snapshots")

          // Read the snapshots queue backwards until we get the latest snapshot in memory.
          val snapSeq = snapshotQueue.reduceWhile[Option[Queue[SnapshotOrder]]]({
            case (None, SnapshotItem(order, time, index, total))
              if index == total && time >= from && to.forall(_ > time) =>
              (Some(Queue.empty.enqueue(order)), true)
            case (Some(snapshotOrders), SnapshotItem(order, _, index, total)) if index == 1 =>
              (Some(snapshotOrders.enqueue(order)), false)
            case (Some(snapshotOrders), SnapshotItem(order, _, _, _)) =>
              (Some(snapshotOrders.enqueue(order)), true)
            case (None, _) =>
              (None, true)
          }, None, QueueBound.End, TailerDirection.BACKWARD)

          if (snapSeq.isDefined) {
            var state = OrderBookMD[APIOrderEvent]("gdax", topic)
              .addSnapshot(snapSeq.get.head.seq, snapSeq.get)
            eventsQueue.scan({ event =>
              val prevState = state
              if (event.seq == prevState.seq + 1) {
                state = prevState.addEvent(event)
                ref ! state
              }

              (event.seq - 1) <= prevState.seq
            })
          }

          eventsQueue.close()
          snapshotQueue.close()
          ref ! Success

        case (Trades, TimeRange(from, to)) =>
          val tradesQueue: PersistentQueue[TradeMD] =
            queue(dataDir, parseProductId(topic), "trades")
          tradesQueue.scan({ md =>
            if (md.time >= from) ref ! md
            to.forall(md.time < _)
          })
          tradesQueue.close()
          ref ! Success

        case (DepthBook(_), _) =>
          throw new RuntimeException(s"GDAX order book aggregations not yet implemented")
      }
      case None =>
        throw new RuntimeException(s"Unknown data type: $dataType")
    }
  }

  private def queue[T](dataDir: String, product: Pair, name: String) =
    PersistentQueue[T](new File(s"$dataDir/gdax/$product/$name"))

  /**
    * Additional metadata attached to each SnapshotOrder that helps us read snapshots from the
    * queue without maintaining a separate index.
    */
  case class SnapshotItem(order: SnapshotOrder, time: Long, index: Int, total: Int)


  // Has to check that sequence number are actually sequential.
  // Use in memory buffer to handle out-of-order messages.
  class LiveGDAXMarketData(updateFn: OrderBookMD[APIOrderEvent] => Unit)
                          (implicit system: ActorSystem,
                           mat: ActorMaterializer)
    extends OrderBookProvider[APIOrderEvent]("gdax", updateFn) {

    import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
    import io.circe.generic.auto._

    var ws: Option[Websocket] = None
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
        val p: Pair = parseProductId(ev.product_id)

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
      val cli = WebsocketClient[String]("wss://ws-feed.gdax.com") {
        case msg =>
          parseJson[UnparsedAPIOrderEvent](msg) match {
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
      ws = Some(cli.open())

      val s = s"""
           |{
           | "type": "subscribe",
           | "product_ids": [${ps.map(p => s""""${p.toString.toUpperCase}"""").mkString(", ")}],
           | "channels": ["full"]
           |}
        """.stripMargin

      // Subscribe on the websocket
      ws.get ! s

    }
  }

  // How we store API events on disk
  case class APIOrderEvent(`type`: String,
                            product_id: String,
                            seq: Long,
                            time: Long,
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
          Open(order_id.get, parseProductId(product_id), price.get, remaining_size.get,
            Side.parseSide(side.get))
        case "done" =>
          Done(order_id.get, parseProductId(product_id), Side.parseSide(side.get),
            DoneReason.parse(reason.get), price, remaining_size)
        case "change" =>
          Change(order_id.get, parseProductId(product_id), price, new_size.get)
        case "match" =>
          Match(trade_id.get, parseProductId(product_id), time, size.get, price.get,
            Side.parseSide(side.get), maker_order_id.get, taker_order_id.get)
        case "received" =>
          Received(order_id.get, parseProductId(product_id), client_oid,
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
      time.map(ISO8601ToMicros).get, size.map(_.toDouble), price.map(_.toDouble), order_id,
      side, reason, order_type, remaining_size.map(_.toDouble), funds.map(_.toDouble), trade_id,
      maker_order_id, taker_order_id, taker_user_id, user_id, taker_profile_id, profile_id,
      new_size.map(_.toDouble), old_size.map(_.toDouble), new_funds.map(_.toDouble),
      old_funds.map(_.toDouble), last_size.map(_.toDouble), best_bid.map(_.toDouble),
      best_ask.map(_.toDouble), client_oid)
  }
}
