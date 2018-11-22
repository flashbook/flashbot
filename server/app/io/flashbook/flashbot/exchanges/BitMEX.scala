package io.flashbook.flashbot.exchanges

import io.flashbook.flashbot.core.Instrument.{FuturesContract, Index}
import io.flashbook.flashbot.core.Order.Fill
import io.flashbook.flashbot.core._

import scala.concurrent.Future

class BitMEX extends Exchange {
  override def makerFee: Double = ???

  override def takerFee: Double = ???

  override def cancel(id: String, pair: Instrument): Unit = ???

  override def order(req: OrderRequest): Unit = ???

  override def baseAssetPrecision(pair: Instrument): Int = ???

  override def quoteAssetPrecision(pair: Instrument): Int = ???

  override def useFundsForMarketBuys: Boolean = ???

  override def lotSize(pair: Instrument): Option[Double] = ???

  override def instruments =
    Future.successful(Set(BitMEX.XBTUSD, BitMEX.ETHUSD))
}

object BitMEX {

  object XBTUSD extends FuturesContract {
    override def symbol = "XBTUSD"
    override def base = "xbt"
    override def quote = "usd"
    override def settledIn = "xbt"

    override def markPrice(prices: PriceIndex) = 1.0 / prices(symbol)

    override def security = Some(symbol)

    // https://www.bitmex.com/app/seriesGuide/XBT#How-is-the-XBTUSD-Perpetual-Contract-Quoted
    override def PNL(amount: Double, entryPrice: Double, exitPrice: Double) =
      amount * (1.0 / entryPrice - 1.0 / exitPrice)
  }

  object ETHUSD extends FuturesContract {
    override def symbol = "ETHUSD"
    override def base = "eth"
    override def quote = "usd"
    override def settledIn = "xbt"

    val bitcoinMultiplier: Double = 0.000001

    override def markPrice(prices: PriceIndex) = ???

    override def security = Some(symbol)

    // https://www.bitmex.com/app/seriesGuide/ETH#How-Is-The-ETHUSD-Perpetual-Contract-Quoted
    override def PNL(amount: Double, entryPrice: Double, exitPrice: Double) = {
      (exitPrice - entryPrice) * bitcoinMultiplier * amount
    }
  }

  object BXBT extends Index(".BXBT", "xbt", "usd")
}
