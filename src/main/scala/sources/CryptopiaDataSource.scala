package sources

import java.io.File
import java.util.concurrent.Executors

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import core.AggBook._
import core.{Ask, Bid, DataSource, MarketData, Pair, Timestamped, Utils}
import core.Utils.initResource
import data.TimeLog
import io.circe.Json
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

class CryptopiaDataSource extends DataSource {

  val SRC = "cryptopia"

  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(100))

  class OrderFetchService(pairs: Map[String, Seq[Seq[TradePair]]],
                          ordersRef: ActorRef) extends Actor with ActorLogging {
    var allBatches: Queue[Seq[TradePair]] = batches

    implicit val sys: ActorSystem = context.system
    implicit val mat: Materializer = ActorMaterializer()

    // Rate limit is 1000 requests per minute. 900 to be safe.
    val period: Int = 60 * 1000 / 900
    self ! "fetch"
    override def receive: Receive = {
      case "fetch" =>
        allBatches.dequeue match {
          case (batch, queue) =>
            fetchOrders(batch.map(p => Pair(p.Symbol, p.BaseSymbol))) onComplete {
              case Success(orders) =>
                val micros = Utils.currentTimeMicros
                orders.foreach(ordersRef ! (_, micros))
              case Failure(err) =>
                log.error(err, "Failure fetching orders from Cryptopia API")
                throw err

            }
            allBatches = queue
        }
        if (allBatches.isEmpty) {
          allBatches = batches
        }
        Thread.sleep(period)
        self ! "fetch"
    }

    def batches: Queue[Seq[TradePair]] =
      scala.collection.immutable.Queue(pairs.values.toSeq.flatten:_*)
  }

  override def ingest(dataDir: String,
                      topics: Map[String, Json],
                      dataTypes: Map[String, DataSource.DataTypeConfig])
                     (implicit sys: ActorSystem, mat: ActorMaterializer): Unit = {

    // Load markets
    val markets = Await.result(Http().singleRequest(HttpRequest(
      method = HttpMethods.GET,
      uri = "https://www.cryptopia.co.nz/api/GetMarkets"
    )).flatMap(rsp =>
      Unmarshal(rsp.entity).to[CryptopiaResponse[Seq[CryptopiaMarket]]]
    ), 5 seconds).Data.map(m => (m.Label, m)).toMap

    // Load trade pairs
    val tradePairs = Await.result(Http().singleRequest(HttpRequest(
      method = HttpMethods.GET,
      uri = "https://www.cryptopia.co.nz/api/GetTradePairs"
    )).flatMap(rsp =>
      Unmarshal(rsp.entity).to[CryptopiaResponse[Seq[TradePair]]]
    ), 5 seconds).Data
      .filter(_.Status == "OK")
      .groupBy(_.BaseSymbol)
      .mapValues(_.sortWith((a, b) =>
        markets(a.Label).BaseVolume < markets(b.Label).BaseVolume))
      // Batches of 5
      .mapValues(_.grouped(5).toSeq)

    val bookStream = Source
      .actorRef(Int.MaxValue, OverflowStrategy.fail)
      .via(Flow[(Orders, Long)].map(a => ordersToAggBookMD(a._1, a._2)))
      .via(initResource(md =>
        timeLog[AggBookMD](dataDir, md.product, md.dataType)))
      .to(Sink.foreach { case (timeLog, book) =>
        println("Saving agg book", book)
        timeLog.enqueue(book)
      })
      .run

    val numBatches = tradePairs.values.map(_.length).sum
    val orderService = sys.actorOf(Props(
      new OrderFetchService(tradePairs, bookStream)))

    println("Number of batches: " + numBatches)
  }

  private def timeLog[T <: Timestamped](dataDir: String, product: Pair, name: String) =
    TimeLog[T](new File(s"$dataDir/$SRC/$product/$name"))

  override def stream(dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: core.TimeRange): Iterator[MarketData] = {
    ???
  }

  def fetchOrders(pairs: Seq[Pair])(implicit sys: ActorSystem,
                                    ec: ExecutionContext,
                                    mat: Materializer): Future[Seq[Orders]] =
    Http().singleRequest(HttpRequest(
      method = HttpMethods.GET,
      uri = "https://www.cryptopia.co.nz/api/GetMarketOrderGroups/" +
        pairs.map(_.toString.toUpperCase).mkString("-") + "/10"
    )).flatMap(r => Unmarshal(r.entity).to[CryptopiaResponse[Seq[Orders]]])
      .map(_.Data)

  case class CryptopiaResponse[T](Success: Option[Boolean],
                                  Message: Option[String],
                                  Data: T)

  case class CryptopiaOrder(TradePairId: Long,
                            Label: String,
                            Price: Double,
                            Volume: Double,
                            Total: Double)

  case class Orders(TradePairId: Long,
                    Market: String,
                    Buy: Seq[CryptopiaOrder],
                    Sell: Seq[CryptopiaOrder])

  def ordersToAggBookMD(orders: Orders, micros: Long): AggBookMD = {
    var book = AggBook(math.max(orders.Buy.length, orders.Sell.length))
    book = orders.Buy.foldRight(book) { case (order, memo) =>
      memo.updateLevel(Bid, order.Price, order.Volume)}
    book = orders.Buy.foldRight(book) { case (order, memo) =>
      memo.updateLevel(Ask, order.Price, order.Volume)}
    AggBookMD(SRC, orders.Market.toLowerCase, micros, book)
  }

  case class TradePair(Id: Long,
                       Label: String,
                       Currency: String,
                       Symbol: String,
                       BaseCurrency: String,
                       BaseSymbol: String,
                       Status: String,
                       StatusMessage: Option[String],
                       TradeFee: Double,
                       MinimumTrade: Double,
                       MaximumTrade: Double,
                       MinimumBaseTrade: Double,
                       MaximumBaseTrade: Double,
                       MinimumPrice: Double,
                       MaximumPrice: Double)

  case class CryptopiaMarket(TradePairId: Int,
                             Label: String,
                             AskPrice: Double,
                             BidPrice: Double,
                             Low: Double,
                             High: Double,
                             Volume: Double,
                             LastPrice: Double,
                             BuyVolume: Double,
                             SellVolume: Double,
                             Change: Double,
                             Open: Double,
                             Close: Double,
                             BaseVolume: Double,
                             BuyBaseVolume: Double,
                             SellBaseVolume: Double)
}
