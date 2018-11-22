package io.flashbook.flashbot.exchanges

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import io.flashbook.flashbot.core.{Exchange, Instrument, OrderRequest}
import io.circe.Json

class Cryptopia(params: Json)(implicit val system: ActorSystem,
                              val mat: ActorMaterializer) extends Exchange {
  override def makerFee: Double = .002

  override def takerFee: Double = .002

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String, pair: Instrument): Unit = ???

  override def baseAssetPrecision(pair: Instrument): Int = ???

  override def quoteAssetPrecision(pair: Instrument): Int = ???

  override def lotSize(pair: Instrument): Option[Double] = ???
}
