package io.flashbook.flashbot.exchanges

import io.flashbook.flashbot.core.Instrument.FuturesContract
import io.flashbook.flashbot.core.{Exchange, Instrument, MarketData, Order, OrderEvent, OrderRequest, Pair}

import scala.concurrent.Future

class BitMEX extends Exchange {
  override def makerFee: Double = ???

  override def takerFee: Double = ???

  override def cancel(id: String, pair: Pair): Unit = ???

  override def order(req: OrderRequest): Unit = ???

  override def baseAssetPrecision(pair: Pair): Int = ???

  override def quoteAssetPrecision(pair: Pair): Int = ???

  override def useFundsForMarketBuys: Boolean = ???

  override def lotSize(pair: Pair): Option[Double] = ???

  override def instruments =
    Future.successful(Set(BitMEX.XBTUSD, BitMEX.ETHUSD))
}

object BitMEX {

  case object XBTUSD extends FuturesContract {
    override def symbol = "XBTUSD"
    override def base = "xbt"
    override def quote = "usd"
    override def settledIn = Some("xbt")

    // https://www.bitmex.com/app/seriesGuide/XBT#How-is-the-XBTUSD-Perpetual-Contract-Quoted
    override def settlementPrice(prices: Map[Instrument, Double]) = for {
      xbtusdPrice <- prices.map { case (k, v) => (k.symbol, v) }.get(symbol)
      price <- 1.0 / xbtusdPrice
    } yield price
  }

  case object ETHUSD extends FuturesContract {
    override def symbol = "ETHUSD"
    override def base = "eth"
    override def quote = "usd"
    override def settledIn = Some("xbt")

    val bitcoinMultiplier = 0.000001

    // https://www.bitmex.com/app/seriesGuide/ETH#How-Is-The-ETHUSD-Perpetual-Contract-Quoted
    override def settlementPrice(prices: Map[Instrument, Double]) = for {
      ethusdPrice <- prices.map { case (k, v) => (k.symbol, v) }.get(symbol)
      price <- ethusdPrice * bitcoinMultiplier
    } yield price
  }
}
