package io.flashbook.flashbot.exchanges

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import io.flashbook.flashbot.core.{Exchange, Instrument, OrderRequest}
import io.circe.Json
import io.flashbook.flashbot.core.Instrument.CurrencyPair

class Bitstamp(params: Json)(implicit val system: ActorSystem,
                             val mat: ActorMaterializer) extends Exchange {
  override def makerFee: Double = .0005

  override def takerFee: Double = .0005

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String, pair: Instrument): Unit = ???

  override def baseAssetPrecision(pair: Instrument): Int = pair match {
    case CurrencyPair("eur", "usd") => 5
    case _ => 8
  }

  override def quoteAssetPrecision(pair: Instrument): Int = pair match {
    case CurrencyPair("xrp", "eur") => 5
    case CurrencyPair("xrp", "usd") => 5
    case CurrencyPair("eur", "usd") => 5
    case CurrencyPair(_, "usd") => 2
    case CurrencyPair(_, "eur") => 2
    case _ => 8
  }

  override def lotSize(pair: Instrument): Option[Double] = None
//    case Pair(_, "usd") => 5
//    case Pair(_, "eur") => 5
//    case Pair(_, "btc") => .001
//  })
}
