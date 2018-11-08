package exchanges

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import core.{Exchange, OrderRequest, Pair}
import io.circe.Json

class Cryptopia(params: Json)(implicit val system: ActorSystem,
                              val mat: ActorMaterializer) extends Exchange {
  override def makerFee: Double = .002

  override def takerFee: Double = .002

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String, pair: Pair): Unit = ???

  override def baseAssetPrecision(pair: core.Pair): Int = ???

  override def quoteAssetPrecision(pair: core.Pair): Int = ???

  override def lotSize(pair: core.Pair): Option[Double] = ???
}
