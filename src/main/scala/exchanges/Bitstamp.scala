package exchanges

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import core.{Exchange, OrderRequest}
import io.circe.Json

class Bitstamp(params: Json)(implicit val system: ActorSystem,
                             val mat: ActorMaterializer) extends Exchange {
  override def makerFee: Double = ???

  override def takerFee: Double = ???

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String): Unit = ???

  override def baseAssetPrecision(pair: core.Pair): Int = ???

  override def quoteAssetPrecision(pair: core.Pair): Int = ???

  override def lotSize(pair: core.Pair): Double = ???
}
