package io.flashbook.flashbot.exchanges

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import io.circe.Json
import io.flashbook.flashbot.core._

class Coinbase(params: Json)(implicit val system: ActorSystem,
                            val mat: ActorMaterializer) extends Exchange {
  override val makerFee = .0 // nice
  override val takerFee = .003 // This is actually based on your volume, but .3 is worst case

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String, pair: Instrument): Unit = ???

  override def baseAssetPrecision(pair: Instrument): Int = ???

  override def quoteAssetPrecision(pair: Instrument): Int = ???

  override def useFundsForMarketBuys: Boolean = true

  override def lotSize(pair: Instrument): Option[Double] = ???
}
