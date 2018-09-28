package exchanges

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import core.{Exchange, OrderRequest, Pair}
import io.circe.Json

class Bitstamp(params: Json)(implicit val system: ActorSystem,
                             val mat: ActorMaterializer) extends Exchange {
  override def makerFee: Double = .0005

//  override def takerFee: Double = .0005
  override def takerFee: Double = -.00025

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String): Unit = ???

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

  override def lotSize(pair: Pair): Option[Double] = None
//    case Pair(_, "usd") => 5
//    case Pair(_, "eur") => 5
//    case Pair(_, "btc") => .001
//  })
}
