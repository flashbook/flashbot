package exchanges

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import core.{Exchange, OrderRequest, Pair}
import io.circe.Json

class Bitfinex(params: Json)(implicit val system: ActorSystem,
                             val mat: ActorMaterializer) extends Exchange {
  override def makerFee = 0

  override def takerFee = -0.00025

  override def order(req: OrderRequest) = ???

  override def cancel(id: String, pair: core.Pair) = ???

  override def baseAssetPrecision(pair: Pair): Int = pair match {
    case Pair("eur", "usd") => 5
    case _ => 8
  }

  override def quoteAssetPrecision(pair: Pair): Int = pair match {
    case Pair("xrp", "eur") => 5
    case Pair("xrp", "usd") => 5
    case Pair("eur", "usd") => 5
    case Pair(_, "usd") => 2
    case Pair(_, "eur") => 2
    case _ => 8
  }

}
