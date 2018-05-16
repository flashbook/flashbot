package core

import java.time.Instant
import java.util.Base64

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.util.ByteString

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import io.circe.Encoder

object Client {

  import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
  import io.circe.syntax._
  import io.circe.Printer

  val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

  class GDAXClient(implicit system: ActorSystem,
                   mat: ActorMaterializer) {
    // API key:
    val key: Array[Byte] =
      Base64.getDecoder.decode("qAqaYAas+qJkhdu3NaeNGmAvJpA0+koeDs7yzs/GAbYkGp0wcfxS1rOhiuMiBctewFgUxo5BFCFYskuxIkQlVQ==")

    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    def placeLimitOrder(req: LimitOrderRequest)(implicit en: Encoder[LimitOrderRequest])
    : Future[PlaceOrderResponse] = {
      request(POST, "/orders", Some(printer.pretty(req.asJson)))
        .flatMap(rsp => Unmarshal(rsp.entity).to[PlaceOrderResponse])
    }

    def placeMarketOrder(req: MarketOrderRequest)(implicit en: Encoder[MarketOrderRequest])
    : Future[PlaceOrderResponse] = {
      request(POST, "/orders", Some(printer.pretty(req.asJson)))
        .flatMap(rsp => Unmarshal(rsp.entity).to[PlaceOrderResponse])
    }

    def cancelOrder(id: String): Future[akka.Done] =
      request(DELETE, s"/orders/$id").map(_ => akka.Done)

    private def request(method: HttpMethod,
                        path: String,
                        entity: Option[String] = None): Future[HttpResponse] = {
      val timestamp = Instant.now.getEpochSecond.toString
      val prehash = timestamp + method.value + path + entity.getOrElse("")

      Http().singleRequest(HttpRequest(
        method = method,
        uri = s"https://api.gdax.com$path",
        entity = entity
          .map(HttpEntity(ContentTypes.`application/json`, _))
          .getOrElse(HttpEntity.Empty),
        headers = List(
          headers.RawHeader("CB-ACCESS-KEY", "215a61f9c25687d8e65402c67e020e4d"),
          headers.RawHeader("CB-ACCESS-SIGN",
            Base64.getEncoder.encodeToString(signature(prehash))),
          headers.RawHeader("CB-ACCESS-TIMESTAMP", timestamp),
          headers.RawHeader("CB-ACCESS-PASSPHRASE", "prgdnsi2zee")
        )
      ))
    }

    private def signature(prehash: String): Array[Byte] = {
      val secret = new SecretKeySpec(key, "HmacSHA256")
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(secret)
      mac.doFinal(prehash.getBytes)
    }
  }

}
