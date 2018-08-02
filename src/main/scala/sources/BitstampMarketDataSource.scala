package sources

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import com.pusher.client.channel.{Channel, ChannelEventListener, SubscriptionEventListener}
import com.pusher.client.connection.{ConnectionEventListener, ConnectionState, ConnectionStateChange}
import com.pusher.client.{Pusher, PusherOptions}
import core.Order.{Buy, Sell}
import core.OrderBook.{OrderBookMD, SnapshotOrder}
import core.{Canceled, DataSource, Filled, MarketData, OrderBook, OrderChange, OrderDone, OrderEvent, OrderOpen, Pair, RawOrderEvent, TimeRange}
import core.Utils.parseProductId
import io.circe.Json
import io.circe.optics.JsonPath._
import io.circe.generic.auto._
import io.circe.parser._

import scala.collection.immutable.Queue
import scala.concurrent.Future
import scala.util.{Failure, Success}

object BitstampMarketDataSource {
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
    private var seenEvent = false
    private var eventQueue = Queue.empty[BitstampBookEvent]
    private var state: Option[OrderBookMD[BitstampBookEvent]] = None

    override def receive: Receive = {
      case event: BitstampBookEvent =>

        // Add event to queue.
        eventQueue = eventQueue.enqueue(event)

        // Flush the queue if a current book state exists.
        if (state.isDefined) {
          flushEvents()
        }

        // If it's the first time that we're seeing an event, then request the snapshot.
        if (!seenEvent) {
          seenEvent = true

          Future {
            Thread.sleep(1000)
            Http().singleRequest(HttpRequest(
              uri = s"https://www.bitstamp.net/api/v2/order_book/${formatPair(pair)}?group=2"
            )).flatMap(r => Unmarshal(r.entity).to[BitstampBookSnapshot]) onComplete {
              case Success(snapshot) => self ! snapshot
              case Failure(err) => throw err
            }
          }
        }

      case snapshot: BitstampBookSnapshot =>
        val seq = snapshot.microtimestamp.toLong
        def mapOrder(isBid: Boolean)(o: Seq[String]) =
          SnapshotOrder(pair.toString, seq, isBid, o(2), o.head.toDouble, o(1).toDouble)

        state = Some(OrderBookMD("bitstamp", pair.toString)
          .addSnapshot(seq, snapshot.bids.map(mapOrder(isBid = true)) ++
              snapshot.asks.map(mapOrder(isBid = false))))
        flushEvents()
    }

    def flushEvents(): Unit = {
      while (eventQueue.nonEmpty) {
        eventQueue.dequeue match {
          case (event, queue) =>
            eventQueue = queue
            // Drop all events that have a lower sequence number than the current state
            if (event.seq > state.get.seq) {
              state = Some(state.get.addEvent(event))
              bookReceiver ! state.get
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
      val pairStr = formatPair(pair)

      // Subscribe to the live orders channel for this pair
      val channel = pusher.subscribe(s"live_orders_$pairStr", new ChannelEventListener {
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
              root.event_type.int.getOption(json).get,
              root.price.double.getOption(json).get,
              root.datetime.string.getOption(json).get,
              root.amount.double.getOption(json).get,
              root.id.long.getOption(json).get,
              root.microtimestamp.string.getOption(json).get
            )
          }
        })
      }

      // Bind events
      bindEvent(CREATED)
      bindEvent(CHANGED)
      bindEvent(DELETED)
    })

  }

  override def stream(dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: TimeRange): Iterator[MarketData] = ???
}
