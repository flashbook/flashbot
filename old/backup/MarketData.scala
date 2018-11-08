package core

import akka.actor.{Actor, ActorSystem}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import com.github.andyglow.websocket.{Websocket, WebsocketClient}
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.collection.immutable.{HashMap, Queue, SortedSet}
import scala.concurrent.Future
import utils.Utils._

import scala.util.{Failure, Success}


sealed trait MarketCommand
final case class Snapshot(product: (String, String),
                          snap: (Long, Seq[SnapshotOrder])) extends MarketCommand
final case class Subscribe(ps: Set[(String, String)]) extends MarketCommand

abstract class MarketData(updateFn: MarketState => Unit)
  extends Actor {

  def getSnapshot(product: (String, String),
                  first: APIOrderEvent): Future[(Long, Seq[SnapshotOrder])]

  def ingest(ps: Set[(String, String)], onEvent: APIOrderEvent => Unit): Unit

  val log = Logging(context.system, this)
  var states: Map[(String, String), MarketState] = HashMap()
  var events: Map[(String, String), Queue[APIOrderEvent]] = HashMap()
  var seenProducts: Set[(String, String)] = Set.empty
  var initialized: Boolean = false

  override def receive: Receive = {
    case Subscribe(ps) =>
      log.info(s"SUBSCRIBE ${ps}")
      // Just to be safe. You can only subscribe once.
      if (initialized)
        crash("MarketData already initialized")
      initialized = true

      // Initialize market states for each product
      ps.foreach({ p =>
        states = states + (p -> MarketState(p))
        events = events + (p -> Queue.empty)
      })

      // Subscribe to all products and send events to self
      ingest(ps, self ! _)

    case Snapshot(p, (seq, orders)) =>
      states = states + (p -> states(p).addSnapshot(seq, orders))

      println("got snapshot", p, seq, orders.length)

      // Go through all events and apply them if their seq # is greater than that of the snapshot
      while (events(p).nonEmpty)
        events(p).dequeue match {
          case (e, q) =>
            events = events + (p -> q)
            processEvent(e)
        }

    case ev: APIOrderEvent =>
      val p = parseProductId(ev.product_id)

      // We wait until we receive the first event for the product to request the snapshot.
      // That way there's no risk of missing events between the snapshot and the first event.
      if (!(seenProducts contains p)) {
        getSnapshot(p, ev) onComplete {
          case Success(snapshot) => self ! Snapshot(p, snapshot)
          case Failure(err) => throw err
        }
        seenProducts = seenProducts + p
      }

      if (!states(p).isReady)
        events = events + (p -> events(p).enqueue(ev))
      else
        processEvent(ev)

  }

  def processEvent(e: APIOrderEvent): Unit = {
    val p = parseProductId(e.product_id)
    val ev = e.toOrderEvent

    if (e.sequence.get >= states(p).seq) {
      states = states + (p -> states(p).copy(
        seq = e.sequence.get,
        book = states(p).book.processOrderEvent(ev),
        rawEvent = Some(e),
        time = e.time.get,
        // Price is the price of last trade
        price = ev match {
          case Match(_, _, _, price, _, _, _) => Some(price)
          case _ => states(p).price
        }
      ))

      updateFn(states(p))
    }
  }

  def crash(err: String): Unit = {
    log.error(err)
    throw new RuntimeException(err)
  }
}


// Reads data from disk. Events must already be in the correct order.
class HistoricalMarketData(updateFn: MarketState => Unit, start: Long, end: Long)
  extends MarketData(updateFn) {

  private val conf = new SparkConf().setMaster("local").setAppName("trader-tron-history")
  private val spark = SparkSession.builder().config(conf).getOrCreate()

  import spark.implicits._

  override def getSnapshot(p: (String, String),
                           first: APIOrderEvent): Future[(Long, Seq[SnapshotOrder])] =
    Future {
      val allOrders = spark
        .read.parquet("spark-warehouse/snapshots")
        .filter($"product" === productToStr(p))

      // Get the sequence id of the snapshot
      val seq = allOrders
        .filter($"seqId" >= first.sequence.get)
          .lim
        .as[SnapshotOrder].first().seqId

      // Select all orders for the snapshot
      val orders: Seq[SnapshotOrder] = allOrders
        .filter($"seqId" === seq)
        .as[SnapshotOrder]
        .collect

      (seq, orders)
    }

  override def ingest(ps: Set[(String, String)], onEvent: APIOrderEvent => Unit): Unit =
    spark
      .read.parquet("spark-warehouse/events")
      .filter($"product_id".isin(ps.toList.map(productToStr):_*))
      .as[APIOrderEvent]
      .foreach(onEvent)
}


// Has to check that sequence number are actually sequential.
// Use in memory buffer to handle out-of-order messages.
class LiveMarketData(updateFn: MarketState => Unit)(implicit system: ActorSystem,
                                                    mat: ActorMaterializer)
  extends MarketData(updateFn) {

  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  import io.circe.generic.auto._

  var ws: Option[Websocket] = None
  var buffers: Map[(String, String), (Long, SortedSet[APIOrderEvent])] = HashMap()
  var subscribed = false

  case class OrderBookResponse(sequence: Long,
                               bids: List[(String, String, String)],
                               asks: List[(String, String, String)])

  override def getSnapshot(p: (String, String),
                           first: APIOrderEvent): Future[(Long, Seq[SnapshotOrder])] = {
    val ps = productToStr(p)
    Http().singleRequest(HttpRequest(
      uri = s"https://api.gdax.com/products/$ps/book?level=3"))
      .flatMap(r => Unmarshal(r.entity)
        .to[OrderBookResponse]
        .map(x => {
          val bids = x.bids.map(o => SnapshotOrder(ps,
            x.sequence, bid = true, o._3, o._1.toDouble, o._2.toDouble))
          val asks = x.asks.map(o => SnapshotOrder(ps,
            x.sequence, bid = false, o._3, o._1.toDouble, o._2.toDouble))
          (x.sequence, bids ++ asks)
        })
      )
    }

  override def ingest(ps: Set[(String, String)], onEvent: APIOrderEvent => Unit): Unit = {
    // This is where we process every event coming in from the wire that occurs after we get
    // the initial "subscriptions" event. They are not guaranteed to be ordered correctly.
    def ingestEvent(ev: UnparsedAPIOrderEvent): Unit = {
      val p: (String, String) = parseProductId(ev.product_id)

      // If this is the first event for this product, then initialize the buffer.
      if (!buffers.isDefinedAt(p)) {
        buffers = buffers + (p -> (0, SortedSet.empty[APIOrderEvent]))
      }

      // Parse event into an APIOrderEvent and add it to buffer.
      buffers = buffers + (p -> (buffers(p)._1, buffers(p)._2 + ev.parse))

      // Now, replay the buffer while the head is sequential to the running seq number.
      while (buffers(p)._2.nonEmpty &&
        (buffers(p)._1 == buffers(p)._2.head.sequence.get - 1 || buffers(p)._1 == 0)) {
        val head = buffers(p)._2.head
        onEvent(head)
        buffers = buffers + (p -> (head.sequence.get, buffers(p)._2 - head))
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
            // Hack. A parsing error is interpreted as the only type of message that we don't know
            // how to parse: A msg of type "subscriptions". Now, for some reason, there are usually
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
         | "product_ids": [${ps.map(p => s""""${productToStr(p)}"""").mkString(", ")}],
         | "channels": ["full"]
         |}
      """.stripMargin

    // Subscribe on the websocket
    ws.get ! s

  }
}
