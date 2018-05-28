package sources

import java.io.File

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.github.andyglow.websocket.{Websocket, WebsocketClient}
import core.AggBook.AggBookMD
import core.{AggBook, DataSource, MarketData, Pair, Timestamped, Trade, TradeMD}
import core.DataSource.{DepthBook, FullBook, Trades, parseBuiltInDataType}
import core.Utils._
import core.{Ask, Bid}
import data.TimeLog
import data.TimeLog.TimeLog
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._

import scala.collection.immutable.Queue
import scala.concurrent.Future
import scala.util.{Failure, Success}

class BinanceMarketDataSource extends DataSource {

  val SRC = "binance"
  val BOOK_DEPTH = 1000
  val MAX_PRODUCTS = 10000
  val SNAPSHOT_INTERVAL = 100000

  override def ingest(dataDir: String,
                      topics: Map[String, Json],
                      dataTypes: Map[String, DataSource.DataTypeConfig])
                     (implicit system: ActorSystem,
                      mat: ActorMaterializer): Unit = {

    val aggBookStream = Source
      .actorRef[(AggBookMD, DepthUpdateEvent)](Int.MaxValue, OverflowStrategy.fail)
      .groupBy(MAX_PRODUCTS, _._1.product)

      // Create one set of TimeLogs per product
      .via(initResource(md => (
        timeLog[DepthUpdateEvent](dataDir, md._1.product, md._1.dataType + "/events"),
        timeLog[AggBookMD](dataDir, md._1.product, md._1.dataType + "/snapshots")
      )))

      // Keep count of events
      .via(withIndex)

      // Persist books
      .to(Sink.foreach {
        case (index, ((eventsLog, snapshotsLog), (book, event))) =>
          // Always save the event
          eventsLog.enqueue(event)

          // Occasionally save the full state as a snapshot
          if (index % SNAPSHOT_INTERVAL == 0)
            snapshotsLog.enqueue(book)
      }).run

    val depthUpdateStream = Source
      .actorRef[(Pair, DepthUpdateEvent)](Int.MaxValue, OverflowStrategy.fail)
      .groupBy(MAX_PRODUCTS, _._1)

      // At times, depth updates will be coming in from two different web sockets. This is
      // overlap is OK as long as the "u" field (last update id) of the merged stream keeps
      // increasing because depth update applications are idempotent. So here, we filter out
      // any events that are outdated because they don't push the last update id forward.
      .via(deDupeStream(_._2.u))

      // Create one book depth provider per product
      .via(initResource(ev =>
          system.actorOf(Props(new AggBookProvider(ev._1, aggBookStream ! (_, ev._2))))))

      // Pass along to the agg book provider
      .to(Sink.foreach { case (ref, (_, e: DepthUpdateEvent)) => ref ! e }).run

    val tradeStream = Source
      .actorRef[TradeMD](Int.MaxValue, OverflowStrategy.fail)
      .groupBy(MAX_PRODUCTS, _.product)

      // De-dupe trades. Duplicates occur due to WebSocket rotation in the trade provider.
      .via(deDupeStream(_.data.id.toLong))

      // Create one TimeLog per product
      .via(initResource(md => timeLog[TradeMD](dataDir, md.product, md.dataType)))

      // Persist trades
      .to(Sink.foreach { case (timeLog, md) => timeLog.enqueue(md) }).run

    dataTypes
      .map { case (k, v) => (parseBuiltInDataType(k).get, v) }
      .keySet
      .foreach {
        case DepthBook(depth) =>
          system.actorOf(Props(
            new BinanceEventsProvider[DepthUpdateEvent](
              "depth",
              topics.keySet.map(parseProductId),
              depthUpdateStream ! (_, _)
            )
          ))

        case Trades =>
          system.actorOf(Props(
            new BinanceEventsProvider[TradeEvent](
              "trade",
              topics.keySet.map(parseProductId),
              (product, t) => tradeStream ! TradeMD(SRC, product.toString,
                Trade(t.t.toString, t.T * 1000000, t.p.toDouble, t.q.toDouble))
            )
          ))

        case FullBook =>
          throw new RuntimeException("Binance doesn't provide full order book streams")
      }
  }

  override def stream(sink: Sink[MarketData, NotUsed],
                      dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: core.TimeRange): Unit = {
    val ref = Source.actorRef(Int.MaxValue, OverflowStrategy.fail).to(sink).run
    parseBuiltInDataType(dataType) match {
      case Some(DepthBook(BOOK_DEPTH)) =>

      case Some(DepthBook(x)) =>
        throw new RuntimeException(s"Invalid depth: $x")

      case Some(Trades) =>
        val tradesLog: TimeLog[TradeMD] = timeLog(dataDir, parseProductId(topic), dataType)
        tradesLog.scan(timeRange.from) { md =>
          ref ! md
          timeRange.to.forall(md.time < _)
        }
        tradesLog.close()
        ref ! Status.Success

      case Some(_) =>
        throw new RuntimeException(s"Unsupported data type: $dataType")
      case None =>
        throw new RuntimeException(s"Unknown data type: $dataType")
    }
  }

  /**
    * Collects events from the Binance WebSocket API. Rotates WebSocket connections periodically
    * because Binance shuts down single connections that are open for over 24 hours.
    */
  class BinanceEventsProvider[T <: RspBody]
  (streamName: String, products: Set[Pair], updateFn: (Pair, T) => Unit) extends Actor {

    case class StreamWrap(stream: String, data: RspBody)

    private val wsRotatePeriodMillis: Long = 1000 * 60 * 60

    private val symbols = products.foldLeft(Map.empty[String, Pair]) {
      case (memo, pair) => memo + ((pair.base + pair.quote) -> pair)
    }

    var mainWebSocket: Websocket = createClient.open()
    var tempWebSocket: Option[Websocket] = None
    var lastRotation: Long = -1

    override def receive: Receive = {
      // Incoming message from a websocket
      case StreamWrap(stream: String, rsp: T) =>
        // Send it to the update function
        updateFn(symbols(stream.split('@').head), rsp)

        // Rotate WebSockets when necessary
        if (lastRotation == -1) {
          lastRotation = rsp.timeMillis
        } else if (rsp.timeMillis - lastRotation > wsRotatePeriodMillis) {
          lastRotation = rsp.timeMillis
          if (tempWebSocket.isDefined) {
            throw new RuntimeException("WebSocket rotation error")
          }

          tempWebSocket = Some(createClient.open())

          // Swap WebSocket references after a reasonable amount of time
          Future {
            Thread.sleep(5000)
            self ! "swap"
          }
        }

      case "swap" =>
        if (tempWebSocket.isEmpty) {
          throw new RuntimeException("WebSocket swap error")
        }

        mainWebSocket.close
        mainWebSocket = tempWebSocket.get
        tempWebSocket = None
    }

    def createClient: WebsocketClient[String] =
      WebsocketClient[String](s"wss://stream.binance.com:9443/streams?streams=" +
        symbols.keySet.map(_ + "@" + streamName).mkString("/")) {
        case str =>
          parseJson[StreamWrap](str) match {
            case Right(wrap) =>
              self ! _
            case Left(a) =>
              throw new RuntimeException(a)
          }
      }
  }

  /**
    * Turn a depth update event stream into a stream of aggregated order books by requesting
    * a depth snapshot for every product and playing the depth update events on top.
    */
  class AggBookProvider(product: Pair, updateFn: AggBookMD => Unit) extends Actor {

    private var buffer = Queue.empty[DepthUpdateEvent]
    private var hasRequestedSnapshot = false
    private var state: Option[AggBook] = None

    override def receive: Receive = {
      case event: DepthUpdateEvent =>
        // Always enqueue to the buffer
        buffer = buffer.enqueue(event)

        // If this is the first event, request the snapshot
        if (!hasRequestedSnapshot) {
          hasRequestedSnapshot = true
          getSnapshot(product) onComplete {
            case Success(snapshot) => self ! snapshot
            case Failure(err) => throw err
          }
        } else if (state.isDefined) {
          // Otherwise flush if we already have a state
          flushBuffer()
        }

      case DepthSnapshotBody(lastUpdateId, bids, asks) =>
        // Turn the depth snapshot into the first state
        state = Some(applyPricePoints(AggBook(BOOK_DEPTH), bids, asks))

        // Now replay all events in buffer, dropping ones that occurred before the snapshot.
        flushBuffer(Some(lastUpdateId))
    }

    private def flushBuffer(lastUpdateId: Option[Long] = None): Unit = {
      while (buffer.nonEmpty) {
        buffer.dequeue match { case (event, newBuffer) =>
          if (lastUpdateId.forall(_ < event.u)) {
            state = state.map(book => applyPricePoints(book, event.b, event.a))
            updateFn(AggBookMD(SRC, product.toString, event.E, state.get))
          }
          buffer = newBuffer
        }
      }
    }

    private def getSnapshot(p: Pair): Future[DepthSnapshotBody] =
      Http().singleRequest(HttpRequest(
        uri = "https://www.binance.com/api/v1/depth?" +
          s"symbol=${(p.base + p.quote).toUpperCase}&limit=$BOOK_DEPTH"
      )).flatMap(r => Unmarshal(r.entity).to[DepthSnapshotBody])
  }

  /*
   * Helper functions
   */

  private def timeLog[T](dataDir: String, product: Pair, name: String) =
    TimeLog[T](new File(s"$dataDir/$SRC/$product/$name"))

  private def parsePricePoint(point: Seq[Json]): (Double, Double) =
    (point.head.asString.get.toDouble, point(1).asString.get.toDouble)

  private def applyPricePoints(book: AggBook,
                               bids: Seq[Seq[Json]],
                               asks: Seq[Seq[Json]]): AggBook = {
    val bidsOnly = bids.foldLeft(book)((memo, point) =>
      memo.updateLevel(Bid, parsePricePoint(point)._1, parsePricePoint(point)._2))
    val bidsAndAsks = asks.foldLeft(bidsOnly)((memo, point) =>
      memo.updateLevel(Ask, parsePricePoint(point)._1, parsePricePoint(point)._2))
    bidsAndAsks
  }


  /*
   * Data formats of WebSocket and API responses
   */

  sealed trait RspBody {
    def timeMillis: Long
  }

  case class TradeEvent(e: String, // Event type
                        E: Long, // Event time in millis
                        s: String, // Symbol
                        t: Long, // Trade ID
                        p: String, // Price
                        q: String, // Quantity
                        b: Long, // Buyer order id
                        a: Long, // Seller order id
                        T: Long, // Trade time
                        m: Boolean, // Whether the buyer is the market maker
                        M: Boolean // Unknown ... ignore
  ) extends RspBody {
    override def timeMillis: Long = E
  }

  case class DepthUpdateEvent(e: String,
                              E: Long, // Event time in millis
                              s: String, // Symbol
                              U: Long, // First update id in event
                              u: Long, // Final update id in event
                              b: Seq[Seq[Json]], // Bid updates
                              a: Seq[Seq[Json]] // Ask updates
  ) extends RspBody {
    override def timeMillis: Long = E
  }


  case class DepthSnapshotBody(lastUpdateId: Long,
                               bids: Seq[Seq[Json]],
                               asks: Seq[Seq[Json]])
}
