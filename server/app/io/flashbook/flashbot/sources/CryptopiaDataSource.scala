package io.flashbook.flashbot.sources

import java.io.File
import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import io.flashbook.flashbot.core.AggBook._
import io.flashbook.flashbot.core.{Ask, Bid, DataSource, MarketData, Timestamped, AggBook => _, _}
import io.flashbook.flashbot.util
import io.flashbook.flashbot.core.DataSource._
import io.flashbook.flashbot.engine.TimeLog.TimeLog
import io.circe.Json
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.flashbook.flashbot.core.Instrument.CurrencyPair
import io.flashbook.flashbot.engine.TimeLog

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

class CryptopiaDataSource(topics: Map[String, Json],
                          dataTypes: Map[String, DataTypeConfig])
    extends DataSource(topics, dataTypes) {

  val SRC = "cryptopia"
  val BOOK_DEPTH = 10

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
            fetchOrders(batch.map(p => CurrencyPair(p.Symbol, p.BaseSymbol))) onComplete {
              case Success(orders) =>
                val micros = util.time.currentTimeMicros
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

  override def discoverTopics(implicit sys: ActorSystem,
                              mat: ActorMaterializer): Future[Set[String]] = {

    val markets = Await.result(Http().singleRequest(HttpRequest(
      method = HttpMethods.GET,
      uri = "https://www.cryptopia.co.nz/api/GetMarkets"
    )).flatMap(rsp =>
      Unmarshal(rsp.entity).to[CryptopiaResponse[Seq[CryptopiaMarket]]]
    ), 5 seconds).Data
      .map(m => m.copy(Label = m.Label.toLowerCase.replaceAll("/", "_")))
      .groupBy(_.Label.split("_").last)
      .mapValues(_.sortWith((a, b) => a.Volume > b.Volume).take(50))
      .values
      .flatten
      .groupBy(_.Label)
      .mapValues(_.head)

    println(s"markets size: ${markets.size}")

    Await.result(Http().singleRequest(HttpRequest(
      method = HttpMethods.GET,
      uri = "https://www.cryptopia.co.nz/api/GetTradePairs"
    )).flatMap(rsp =>
      Unmarshal(rsp.entity).to[CryptopiaResponse[Seq[TradePair]]]
    ), 5 seconds).Data
      .map(m => m.copy(Label = m.Label.toLowerCase.replaceAll("/", "_")))
      .filter(_.Status == "OK")
      .filter(markets isDefinedAt _.Label)
      .filter(m => markets(m.Label).Volume > 0)
      .map(_.Label).toSet

    ???
  }

  override def ingestGroup(topics: Set[String], dataType: String)
                          (implicit sys: ActorSystem,
                           mat: ActorMaterializer): Map[String, Source[Timestamped, NotUsed]] = {

//    val newTopics = topics.toSeq.flatMap {
//      case ("*", value) => discoverTopics.toSeq.map((_, value))
//      case kv => Seq(kv)
//    }.toMap
//
//    // Load trade pairs
//    val tradePairs = Await.result(Http().singleRequest(HttpRequest(
//      method = HttpMethods.GET,
//      uri = "https://www.cryptopia.co.nz/api/GetTradePairs"
//    )).flatMap(rsp =>
//      Unmarshal(rsp.entity).to[CryptopiaResponse[Seq[TradePair]]]
//    ), 5 seconds).Data
//      .filter(_.Status == "OK")
//      .filter(newTopics contains _.Label.toLowerCase.replaceAll("/", "_"))
//      .groupBy(_.BaseSymbol)
//      // Batches of 5
//      .mapValues(_.grouped(5).toSeq)
//
//    val bookStream = Source
//      .actorRef(Int.MaxValue, OverflowStrategy.fail)
//      .via(Flow[(Orders, Long)].map(a => ordersToAggBookMD(a._1, a._2)))
//      .groupBy(10000, _.product)
//      .via(util.stream.initResource(md =>
//        timeLog[AggBookMD](dataDir, md.product, md.dataType)))
//      .to(Sink.foreach {
//        case (timeLog, book) =>
//          timeLog.enqueue(book)
//      })
//      .run
//
//    sys.actorOf(Props(new OrderFetchService(tradePairs, bookStream)))
    ???
  }

  private def timeLog[T <: Timestamped](dataDir: String, product: String, name: String) =
    TimeLog[T](new File(s"$dataDir/$SRC/$product/$name"))

  override def stream(dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: TimeRange): Iterator[MarketData] = {
    parseBuiltInDataType(dataType) match {
      case Some(DepthBook(BOOK_DEPTH)) =>
        var tl: TimeLog[AggBookMD] = timeLog[AggBookMD](dataDir, topic, dataType)
        tl.scan[Long](timeRange.from, _.micros, md => md.micros < timeRange.to)()
    }
  }

  def fetchOrders(pairs: Seq[CurrencyPair])(implicit sys: ActorSystem,
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
    book = orders.Sell.foldRight(book) { case (order, memo) =>
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

  override def ingest(topic: String, dataType: String)(implicit sys: ActorSystem, mat: ActorMaterializer) = ???
}
