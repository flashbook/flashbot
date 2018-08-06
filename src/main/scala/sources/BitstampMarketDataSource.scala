package sources

import java.io.File

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import com.pusher.client.channel.{ChannelEventListener, SubscriptionEventListener}
import com.pusher.client.connection.{ConnectionEventListener, ConnectionStateChange}
import com.pusher.client.Pusher
import core.AggBook.{AggBook, AggBookMD, fromOrderBook}
import core.Order.{Buy, Sell}
import core.OrderBook.{OrderBookMD, SnapshotOrder}
import core.{Canceled, DataSource, Filled, MarketData, OrderChange, OrderDone, OrderEvent, OrderOpen, Pair, RawOrderEvent, TimeRange, Timestamped, Utils}
import core.DataSource.{FullBook, parseBuiltInDataType}
import core.Utils.{initResource, parseProductId}
import data.TimeLog
import io.circe.Json
import io.circe.optics.JsonPath._
import io.circe.parser._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object BitstampMarketDataSource {

  val NAME = "bitstamp"

  val CREATED = "order_created"
  val CHANGED = "order_changed"
  val DELETED = "order_deleted"

  private def formatPair(pair: Pair): String = (pair.base + pair.quote).toLowerCase

  case class BitstampBookEvent(event: String,
                               product: String,
                               order_type: Int,
                               price: Double,
                               datetime: String,
                               amount: Double,
                               id: Long,
                               microtimestamp: String) extends RawOrderEvent {
    override def micros: Long = microtimestamp.toLong

    override def seq: Long = micros // :(

    override def toOrderEvent: OrderEvent = event match {
      case CREATED =>
        OrderOpen(
          id.toString,
          parseProductId(product),
          price,
          amount,
          if (order_type == 0) Buy else Sell)

      case CHANGED =>
        OrderChange(
          id.toString,
          parseProductId(product),
          Some(price),
          amount)

      case DELETED =>
        OrderDone(
          id.toString,
          parseProductId(product),
          if (order_type == 0) Buy else Sell,
          if (amount == 0) Filled else Canceled,
          Some(price),
          Some(amount)
        )
    }
  }

  case class BitstampBookSnapshot(timestamp: String,
                                  microtimestamp: String,
                                  bids: Seq[Seq[String]],
                                  asks: Seq[Seq[String]])

  /**
    * An actor that connects to the Bitstamp API (REST and WebSocket) to reconstruct an
    * order book for the provided product and emit the book to the receiver on each update.
    */
  class BitstampOrderBookProvider(pair: Pair,
                                  pusher: Pusher,
                                  bookReceiver: ActorRef) extends Actor {
    implicit val system: ActorSystem = context.system
    implicit val mat: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContext = context.dispatcher

    private val pairStr = formatPair(pair)
    private var seenEvent = false
    private var eventQueue = Queue.empty[BitstampBookEvent]
    private var state: Option[OrderBookMD[BitstampBookEvent]] = None

    // Subscribe to the live orders channel for this pair
    private val channel = pusher.subscribe(s"live_orders_$pairStr", new ChannelEventListener {
      override def onSubscriptionSucceeded(channelName: String): Unit = {
        println(s"Subscribed to $channelName")
      }

      override def onEvent(channelName: String, eventName: String, data: String): Unit = {
        println("Channel event", eventName, data)
      }
    })

    def bindEvent(event: String): Unit = {
      channel.bind(event, new SubscriptionEventListener {
        override def onEvent(channelName: String, eventName: String, data: String): Unit = {
          val json = parse(data).right.get
          val event = BitstampBookEvent(
            eventName,
            pair.toString,
            root.order_type.int.getOption(json).get,
            root.price.number.getOption(json).get.toDouble,
            root.datetime.string.getOption(json).get,
            root.amount.number.getOption(json).get.toDouble,
            root.id.long.getOption(json).get,
            root.microtimestamp.string.getOption(json).get)
          self ! event
        }
      })
    }

    // Bind events
    bindEvent(CREATED)
    bindEvent(CHANGED)
    bindEvent(DELETED)

    override def receive: Receive = {
      case event: BitstampBookEvent =>

        // Add event to queue.
        eventQueue = eventQueue.enqueue(event)

        // Flush the queue if a current book state exists.
        if (state.isDefined) {
          flushEvents(true)
        }

        // If it's the first time that we're seeing an event, then request the snapshot.
        if (!seenEvent) {
          seenEvent = true

          Future {
            Http().singleRequest(HttpRequest(
              uri = s"https://www.bitstamp.net/api/v2/order_book/${formatPair(pair)}?group=2"
            )).flatMap(r => Unmarshal(r.entity).to[BitstampBookSnapshot]) onComplete {
              case Success(snapshot) =>
                self ! snapshot
              case Failure(err) => throw err
            }
          }
        }

      case snapshot: BitstampBookSnapshot =>
        val seq = snapshot.microtimestamp.toLong
        def mapOrder(isBid: Boolean)(o: Seq[String]) =
          SnapshotOrder(pair.toString, seq, isBid, o(2), o.head.toDouble, o(1).toDouble)

        state = Some(OrderBookMD(NAME, pair.toString)
          .addSnapshot(seq, snapshot.bids.map(mapOrder(isBid = true)) ++
              snapshot.asks.map(mapOrder(isBid = false))))
        flushEvents(false)
    }

    def flushEvents(shouldEmit: Boolean): Unit = {
      while (eventQueue.nonEmpty) {
        eventQueue.dequeue match {
          case (event, queue) =>
            eventQueue = queue
            try {
              state = Some(state.get.addEvent(event))
              if (shouldEmit) {
                bookReceiver ! state.get
              }
            } catch {
              case err: Throwable =>
                println("No such element", event)
                err.printStackTrace()
            }
        }
      }
    }
  }
}

class BitstampMarketDataSource extends DataSource {
  import BitstampMarketDataSource._

  override def ingest(dataDir: String,
                      topics: Map[String, Json],
                      dataTypes: Map[String, DataSource.DataTypeConfig])
                     (implicit sys: ActorSystem, mat: ActorMaterializer): Unit = {

    val dts = dataTypes.map { case (k, v) => (parseBuiltInDataType(k).get, v) }

    val bookRef = Source
      .actorRef[OrderBookMD[BitstampBookEvent]](Int.MaxValue, OverflowStrategy.fail)
      .groupBy(1000, _.topic)

      // Aggregate books to 10 price levels.
      .map(_.toAggBookMD(10))

      // De-dupe the books after aggregation.
      .via(Utils.deDupeBy(_.data))

      // Create time logs to write market data to.
      .via(initResource(md =>
          timeLog[AggBookMD](dataDir, md.product, md.dataType)))

      .to(Sink.foreach {
        case (aggBookLog, md) =>
          aggBookLog.enqueue(md)
          println("wrote", md)
      })
      .run

    val pusher = new Pusher("de504dc5763aeef9ff52")

    pusher.connect(new ConnectionEventListener {
      override def onConnectionStateChange(change: ConnectionStateChange): Unit = {
        println("Connection state changed", change.getPreviousState, change.getCurrentState)
      }

      override def onError(message: String, code: String, e: Exception): Unit = {
        println("ERROR", message, code, e)
        throw e
      }
    })

    topics.keySet.map(parseProductId).foreach(pair => {
      sys.actorOf(Props(
        new BitstampOrderBookProvider(pair, pusher, bookRef)),
        s"bitstamp-order-book-provider-$pair")
    })

  }

  private def timeLog[T <: Timestamped](dataDir: String, product: Pair, name: String) =
    TimeLog[T](new File(s"$dataDir/$NAME/$product/$name"))

  /**
    * Additional metadata attached to each SnapshotOrder that helps us read snapshots from the
    * queue without maintaining a separate index.
    */
  case class SnapshotItem(order: SnapshotOrder, micros: Long, index: Int, total: Int)
    extends Timestamped


  override def stream(dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: TimeRange): Iterator[MarketData] = {
    parseBuiltInDataType(dataType) match {
      case Some(x) => (x, timeRange) match {
        case (FullBook, TimeRange(from, to)) =>
          val snapshotLog =
            timeLog[SnapshotItem](dataDir, parseProductId(topic), "book/snapshots")

          // Build the snapshot up
          var snapOrders: Option[Queue[SnapshotOrder]] = None
          for (item <- snapshotLog
              .scanBackwards(_.index != 1 || snapOrders.isEmpty)(snapshotLog.close)) {
            (snapOrders, item) match {

              case (None, SnapshotItem(order, time, index, total))
                if index == total && time >= from && to > time =>
                snapOrders = Some(Queue.empty.enqueue(order))

              case (Some(_), SnapshotItem(order, _, index, _)) if index == 1 =>
                snapOrders = Some(snapOrders.get.enqueue(order))

              case (Some(_), SnapshotItem(order, _, _, _)) =>
                snapOrders = Some(snapOrders.get.enqueue(order))

              case _ =>
            }
          }

          ???

//          if (snapOrders.isDefined) {
//            val eventsQueue =
//              timeLog[BitstampBookEvent](dataDir, parseProductId(topic), "book/events")
//            val seq = snapOrders.get.head.seq
//            var state = OrderBookMD[BitstampBookEvent](NAME, topic).addSnapshot(seq, snapOrders.get)
//            for (event <- eventsQueue.scan(seq + 1, _.seq, { event =>
//              val foundNextEvent = event.seq == state.seq + 1
//              if (foundNextEvent) {
////                state = prevState.addEvent(event)
//              }
//              foundNextEvent
//            })) yield state
//          } else {
//            List.empty.iterator
//          }
      }
    }
  }
}
