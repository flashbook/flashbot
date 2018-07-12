package exchanges

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import core.{Exchange, LimitOrderRequest, MarketOrderRequest, OrderRequest, Pair}
import core.Order.{Fill, Taker}
import core.Order.Side.parseSide
import io.circe.Json
import io.circe.optics.JsonPath._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import shaded.org.apache.commons.codec.binary.Hex

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class Binance(params: Json)(implicit val system: ActorSystem,
                            val mat: ActorMaterializer) extends Exchange {

  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

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
      val queryParams = List(
        ("symbol", formatPair(product)),
        ("side", side.toString.toUpperCase),
        ("type", "MARKET"),
        ("quantity", size.get.toString),
        ("recvWindow", "1000"),
        ("timestamp", System.currentTimeMillis.toString),
        ("newClientOrderId", clientOid),
        ("newOrderRespType", "FULL")
      )

      val totalQueryStr = Query(queryParams:_*).toString
      val signature: String = sign(secretKey, totalQueryStr)

      val finalQuery = Query(("signature", signature) :: queryParams:_*)

      val uri = Uri("https://api.binance.com/api/v3/order").withQuery(finalQuery)

      Http().singleRequest(HttpRequest(
        method = HttpMethods.POST,
        uri = uri,
        headers = List(RawHeader("X-MBX-APIKEY", apiKey))
      )).flatMap(r => Unmarshal(r.entity).to[FullOrderRspRaw]) onComplete {
        case Success(rsp) =>
          rsp.fills.foreach { f =>
            fill(Fill(rsp.orderId.toString, None, takerFee, product, f.price.toDouble,
              f.qty.toDouble, rsp.transactTime * 1000, Taker, parseSide(rsp.side.toLowerCase)))
          }
          tick()
        case Failure(err) =>
          throw err
      }
  }

  override def cancel(id: String): Unit = ???

  override def baseAssetPrecision(pair: Pair): Int = 8

  override def quoteAssetPrecision(pair: Pair): Int = 8

  override def useFundsForMarketBuys: Boolean = false

  def sign(key: String, data: String): String = {
    val sha: Mac = Mac.getInstance("HmacSHA256")
    val spec: SecretKeySpec = new SecretKeySpec(key.getBytes(), "HmacSHA256")
    sha.init(spec)
    new String(Hex.encodeHex(sha.doFinal(data.getBytes())))
  }
}
