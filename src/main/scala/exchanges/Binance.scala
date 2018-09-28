package exchanges

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import core.{Exchange, Filled, LimitOrderRequest, MarketOrderRequest, OrderDone, OrderOpen, OrderReceived, OrderRequest, Pair, Utils}
import core.Order.{Fill, Market, Taker}
import core.Order.Side.parseSide
import io.circe.Json
import io.circe.optics.JsonPath._
import io.circe.generic.auto._
import io.circe.parser._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import shaded.org.apache.commons.codec.binary.Hex

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class Binance(params: Json)(implicit val system: ActorSystem,
                            val mat: ActorMaterializer) extends Exchange {

  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  private val apiKey = root.api_key.string.getOption(params).get
  private val secretKey = root.secret_key.string.getOption(params).get

  private val exchangeInfo = Await.result(requestExchangeInfo, 5 seconds)

  override def makerFee: Double = .0005
  override def takerFee: Double = .0005

  def formatPair(pair: Pair): String = (pair.base + pair.quote).toUpperCase

  case class FillRaw(price: String,
                     qty: String,
                     commission: String,
                     commissionAsset: String)

  case class FullOrderRspRaw(symbol: String,
                             orderId: Long,
                             clientOrderId: String,
                             transactTime: Long,
                             price: String,
                             origQty: String,
                             executedQty: String,
                             status: String,
                             timeInForce: String,
                             `type`: String,
                             side: String,
                             fills: Seq[FillRaw])

  case class ErrRsp(code: Int, msg: String)

  override def order(req: OrderRequest): Unit = req match {
    case LimitOrderRequest(clientOid, side, product, price, size) =>
      ???

    case mo @ MarketOrderRequest(clientOid, side, product, size, _) =>

      println("Order Request: ", mo)

      val queryParams = List(
        ("symbol", formatPair(product)),
        ("side", side.toString.toUpperCase),
        ("type", "MARKET"),
        ("quantity", Utils.stripTrailingZeroes(size.get.toString)),
        ("recvWindow", "5000"),
        ("timestamp", System.currentTimeMillis.toString),
        ("newClientOrderId", clientOid),
        ("newOrderRespType", "FULL")
      )

      val totalQueryStr = Query(queryParams:_*).toString
      val signature: String = sign(secretKey, totalQueryStr)

      val finalQuery = Query(queryParams :+ ("signature", signature):_*)
      val uri = Uri("https://api.binance.com/api/v3/order").withQuery(finalQuery)

      Http().singleRequest(HttpRequest(
        method = HttpMethods.POST,
        uri = uri,
        headers = List(RawHeader("X-MBX-APIKEY", apiKey))
      )).flatMap(rsp => {
        println("Got Binance order HTTP Response Status: ", rsp.status)
        rsp.entity.toStrict(1 second).map(_.data).map(_.utf8String).map { rspStr =>
          if (rsp.status == StatusCodes.OK) {
            Right(parse(rspStr).right.get.as[FullOrderRspRaw].right.get)
          } else {
            Left(parse(rspStr).right.get.as[ErrRsp].right.get)
          }
        }
      }) onComplete {
        case Success(Right(rsp)) =>
          println("SUCCESS")

          val oid = rsp.orderId.toString
          event(OrderReceived(oid, product, Some(clientOid), Market))
          event(OrderDone(oid, product, side, Filled, None, None))

          rsp.fills.foreach { f =>
            val fl = Fill(oid, None, takerFee, product, f.price.toDouble,
              f.qty.toDouble, rsp.transactTime * 1000, Taker, parseSide(rsp.side.toLowerCase))
            fill(fl)
          }
          tick()

        case Success(Left(errRsp)) =>
          println("FAIL")
          println(errRsp)

        case Failure(err) =>
          throw err
      }
  }

  override def cancel(id: String): Unit = ???

  override def baseAssetPrecision(pair: Pair): Int =
    exchangeInfo.symbol(formatPair(pair)).baseAssetPrecision

  override def quoteAssetPrecision(pair: Pair): Int =
    exchangeInfo.symbol(formatPair(pair)).quotePrecision

  override def lotSize(pair: Pair): Option[Double] =
    exchangeInfo.symbol(formatPair(pair)).filters
      .find(_.filterType == "LOT_SIZE").get.stepSize.map(_.toDouble)

  override def useFundsForMarketBuys: Boolean = false

  def sign(key: String, data: String): String = {
    val sha: Mac = Mac.getInstance("HmacSHA256")
    val spec: SecretKeySpec = new SecretKeySpec(key.getBytes(), "HmacSHA256")
    sha.init(spec)
    new String(Hex.encodeHex(sha.doFinal(data.getBytes())))
  }

  override def genOrderId: String = "o" + super.genOrderId.replaceAll("-", "")


  case class RateLimitInfo(rateLimitType: String,
                           interval: String,
                           limit: Long)

  case class FilterInfo(filterType: String,
                        minPrice: Option[String],
                        maxPrice: Option[String],
                        tickSize: Option[String],
                        minQty: Option[String],
                        maxQty: Option[String],
                        stepSize: Option[String],
                        minNotional: Option[String])

  case class SymbolInfo(symbol: String,
                        status: String,
                        baseAsset: String,
                        baseAssetPrecision: Int,
                        quoteAsset: String,
                        quotePrecision: Int,
                        orderTypes: Seq[String],
                        icebergAllowed: Boolean,
                        filters: Seq[FilterInfo])

  case class ExchangeInfo(timezone: String,
                          serverTime: Long,
                          rateLimits: Seq[RateLimitInfo],
                          symbols: Seq[SymbolInfo]) {
    def symbol(sym: String): SymbolInfo = symbols.find(_.symbol == sym).get
  }

  private def requestExchangeInfo: Future[ExchangeInfo] =
    Http().singleRequest(HttpRequest(
      method = HttpMethods.GET,
      uri = Uri("https://api.binance.com/api/v1/exchangeInfo")
    )).flatMap(r => Unmarshal(r.entity).to[ExchangeInfo])
}
