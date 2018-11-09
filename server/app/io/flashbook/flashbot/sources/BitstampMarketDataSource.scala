package io.flashbook.flashbot.sources

import java.io.File

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import com.pusher.client.channel.{ChannelEventListener, SubscriptionEventListener}
import com.pusher.client.connection.{ConnectionEventListener, ConnectionStateChange}
import com.pusher.client.Pusher
import io.flashbook.flashbot.core.AggBook.{AggBook, AggBookMD, fromOrderBook}
import io.flashbook.flashbot.core.Order.{Buy, Sell, Side}
import io.flashbook.flashbot.core.OrderBook.{OrderBookMD, SnapshotOrder}
import io.flashbook.flashbot.core.{Canceled, DataSource, Filled, FuzzyBook, MarketData, Order, OrderChange, OrderDone, OrderEvent, OrderOpen, Pair, RawOrderEvent, TimeRange, Timestamped, Trade, TradeMD, Utils}
import io.flashbook.flashbot.core.DataSource.{DepthBook, FullBook, Trades, parseBuiltInDataType}
import io.flashbook.flashbot.core.Utils.{initResource, parseProductId}
import io.flashbook.flashbot.data.TimeLog
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
          if (order_type == 0) Buy else Sell
        )

      case CHANGED =>
        OrderChange(
          id.toString,
          parseProductId(product),
          Some(price),
          amount
        )

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

  case class BitstampTX(date: String, tid: String, price: String, `type`: String, amount: String)

  def toOrder(side: Side)(shorthand: Seq[String]): Order =
    Order(shorthand(2), side, shorthand(1).toDouble, shorthand.headOption.map(_.toDouble))

  case class BitstampBookSnapshot(timestamp: String,
                                  microtimestamp: String,
                                  bids: Seq[Seq[String]],
                                  asks: Seq[Seq[String]]) {
    def orderSet: Set[Order] = bids.map(toOrder(Buy)).toSet ++ asks.map(toOrder(Sell)).toSet
  }

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
    private var fuzzyBook = FuzzyBook()

    val cName = if (pairStr == "btcusd") "live_orders" else s"live_orders_$pairStr"
    var snapCount: Int = -1

    // Subscribe to the live orders channel for this pair
    private val channel = pusher.subscribe(cName, new ChannelEventListener {
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

    var snapWaits = List(1, 2, 3, 4, 6, 10, 15, 20)

    def requestSnapshot(): Unit = {
      Future {
        Http().singleRequest(HttpRequest(
          uri = s"https://www.bitstamp.net/api/v2/order_book/${formatPair(pair)}?group=2"
        )).flatMap(r => Unmarshal(r.entity).to[BitstampBookSnapshot]) onComplete {
          case Success(snapshot) =>
            self ! snapshot

            Future {
              snapCount = snapCount + 1
              Thread.sleep(snapWaits(math.min(snapWaits.size - 1, snapCount)) * 1000)
              requestSnapshot()
            }
          case Failure(err) => throw err
        }
      }
    }

    override def receive: Receive = {
      case event: BitstampBookEvent =>
        fuzzyBook = fuzzyBook.event(event.micros, event.toOrderEvent)
        if (snapCount > 5) {
          bookReceiver ! OrderBookMD[BitstampBookEvent](NAME, event.product.toString,
            event.micros, Some(event), fuzzyBook.orderBook)
        }

        if (!seenEvent) {
          seenEvent = true
          Future {
            Thread.sleep(1000)
            requestSnapshot()
          }
        }

      case snapshot: BitstampBookSnapshot =>
        fuzzyBook = fuzzyBook.snapshot(snapshot.microtimestamp.toLong, snapshot.orderSet)

    }
  }


  class BitstampTradesProvider(pair: Pair,
                               pusher: Pusher,
                               tradesReceiver: ActorRef) extends Actor {

    implicit val system: ActorSystem = context.system
    implicit val mat: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContext = context.dispatcher

    private val pairStr = formatPair(pair)
    private val cName = if (pairStr == "btcusd") "live_trades" else s"live_trades_$pairStr"

    // Subscribe to the live trades channel for this pair
    private val channel = pusher.subscribe(cName, new ChannelEventListener {
      override def onSubscriptionSucceeded(channelName: String): Unit = {
        println(s"Subscribed to $channelName")
      }

      override def onEvent(channelName: String, eventName: String, data: String): Unit = {
        println("Channel event", eventName, data)
      }
    })

    channel.bind("trade", new SubscriptionEventListener {
      override def onEvent(channelName: String, eventName: String, data: String): Unit = {
        println(data)
        val json = parse(data).right.get
        self ! TradeMD(NAME, pair.toString, Trade(
          root.id.long.getOption(json).get.toString,
          Utils.currentTimeMicros,
          root.price_str.string.getOption(json).get.toDouble,
          root.amount_str.string.getOption(json).get.toDouble,
          if (root.`type`.int.getOption(json).get == 0) Buy else Sell
        ))
      }
    })

    def requestBackfill(): Unit = {
      Future {
        Http().singleRequest(HttpRequest(
          method = HttpMethods.GET,
          uri = s"https://www.bitstamp.net/api/v2/transactions/${formatPair(pair)}/?time=day"
        )).flatMap(r => {
          Unmarshal(r.entity).to[Seq[BitstampTX]]
        }) onComplete {
          case Success(backfill) =>
            self ! backfill
          case Failure(err) =>
            throw err
        }
      }
    }

    Future {
      Thread.sleep(2000)
      requestBackfill()
    }

    var tradeQueue: Option[Queue[TradeMD]] = Some(Queue.empty[TradeMD])
    override def receive: Receive = {
      case md: TradeMD =>
        if (tradeQueue.isDefined) {
          tradeQueue = tradeQueue.map(_.enqueue(md))
        } else {
          tradesReceiver ! md
        }

      case backfill: Seq[BitstampTX] =>
        val backfillTrades = backfill.reverse.map {
          case BitstampTX(date, tid, price, ty, amount) =>
            TradeMD(NAME, pair.toString, Trade(
              tid,
              date.toLong * 1000000 + tid.toLong % 1000000,
              price.toDouble,
              amount.toDouble,
              if (ty.toInt == 0) Buy else Sell
            ))
        }
        backfillTrades.foreach(tradesReceiver ! _)

        tradeQueue.get
          .filter(t => t.data.id.toLong > backfillTrades.last.data.id.toLong)
          .foreach(tradesReceiver ! _)
        tradeQueue = None
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

    dts.foreach {
      case (DepthBook(depth), config) =>
        val bookRef = Source
          .actorRef[OrderBookMD[BitstampBookEvent]](Int.MaxValue, OverflowStrategy.fail)
          .groupBy(1000, _.topic)

          // Aggregate books to 10 price levels.
          .map(_.toAggBookMD(depth))

          // De-dupe the books after aggregation.
          .via(Utils.deDupeBy(_.data))

          // Create time log to write market data to.
          .via(initResource(md =>
            timeLog[AggBookMD](dataDir, md.product, md.dataType)))

          // Persist
          .to(Sink.foreach {
            case (aggBookLog, md) =>
              aggBookLog.enqueue(md)
          })
          .run

        // Create an order book provider for each product.
        topics.keySet.map(parseProductId).foreach(pair => {
          sys.actorOf(
            Props(new BitstampOrderBookProvider(pair, pusher, bookRef)),
            s"bitstamp-order-book-provider-$pair")
        })

      case (Trades, config) =>
        val tradesRef = Source
          .actorRef[TradeMD](Int.MaxValue, OverflowStrategy.fail)
          .groupBy(1000, _.topic)
          .via(initResource(md =>
            timeLog[TradeMD](dataDir, md.product, md.dataType)))
          .to(Sink.foreach {
            case (tradeLog, md) =>
              tradeLog.enqueue(md)
          })
          .run

        topics.keySet.map(parseProductId).foreach(pair => {
          sys.actorOf(
            Props(new BitstampTradesProvider(pair, pusher, tradesRef)),
            s"bitstamp-trades-provider-$pair")
        })
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


  override def stream(dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: TimeRange): Iterator[MarketData] = {
    parseBuiltInDataType(dataType) match {
      case Some(x) => (x, timeRange) match {
        case (DepthBook(depth), TimeRange(from, to)) =>
          val queue = timeLog[AggBookMD](dataDir, parseProductId(topic), s"book_$depth")
          for (aggbook <- queue.scan[Long](from, _.micros, data => data.micros < to)(queue.close))
            yield aggbook.copy(data = aggbook.data.convertToTreeMaps)

        case (Trades, TimeRange(from, to)) =>
          println("Streaming trades???", from, to)
          val queue = timeLog[TradeMD](dataDir, parseProductId(topic), "trades")
          queue.scan[Long](from, _.micros, data => data.micros < to) { () =>
            println("closing queue")
            queue.close()
          }
      }
    }
  }
}
