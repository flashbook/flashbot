package exchanges

import core.{Exchange, MarketData, Order, OrderEvent, OrderRequest, Pair, TradingSession}

class GDAX extends Exchange {
  override val makerFee = .0 // nice
  override val takerFee = .003 // This is actually based on your volume, but .3 is worst case

  override def formatPair(pair: Pair): String = s"${pair.base}-${pair.quote}"

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String): Unit = ???

  override def baseAssetPrecision(pair: Pair): Int = ???

  override def quoteAssetPrecision(pair: Pair): Int = ???

  override def useFundsForMarketBuys: Boolean = true

  override def lotSize(pair: Pair): Double = ???
}
