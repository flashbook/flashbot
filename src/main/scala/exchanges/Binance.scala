package exchanges

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import core.{Exchange, LimitOrderRequest, MarketData, MarketOrderRequest, Order, OrderEvent, OrderRequest, Pair, TradingSession}
import core.Order.Fill
import io.circe.Json
import io.circe.optics.JsonPath._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.util.{Failure, Success}

class Binance(params: Json) extends Exchange {


  private val apiKey = root.api_key.string.getOption(params).get
  private val secretKey = root.secret_key.string.getOption(params).get

  override def makerFee: Double = .0005

  override def takerFee: Double = .0005

  override def formatPair(pair: Pair): String = (pair.base + pair.quote).toUpperCase

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

  override def order(req: OrderRequest): Unit = req match {
    case LimitOrderRequest(clientOid, side, product, price, size) =>
      ???

    case MarketOrderRequest(clientOid, side, product, size, funds) =>
      val uri = Uri("https://api.binance.com")
        .withQuery(Query(
          ("symbol", formatPair(product)),
          ("side", side.toString.toUpperCase),
          ("type", "MARKET"),
          ("quantity", size.get.toString),
          ("recvWindow", "1000"),
          ("timestamp", System.currentTimeMillis.toString),
          ("newClientOrderId", clientOid),
          ("newOrderRespType", "FULL")
        ))

      Http().singleRequest(HttpRequest(
        method = HttpMethods.POST,
        uri = uri
      )).flatMap(r => Unmarshal(r.entity).to[FullOrderRspRaw]) onComplete {
        case Success(rsp) =>
        case Failure(err) =>
      }
  }

  override def cancel(id: String): Unit = ???

  override def baseAssetPrecision(pair: Pair): Int = 8

  override def quoteAssetPrecision(pair: Pair): Int = 8

  override def useFundsForMarketBuys: Boolean = false
}
