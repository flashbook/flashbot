package exchanges

import core.{Exchange, MarketData, Order, OrderEvent, OrderRequest, Pair, TradingSession}
import core.Order.Fill

class Binance extends Exchange {

  override def makerFee: Double = .0005

  override def takerFee: Double = .0005

  override def formatPair(pair: Pair): String = ???

  override def order(req: OrderRequest): Unit = {
    tick()
  }

  override def cancel(id: String): Unit = ???

  override def baseAssetPrecision(pair: Pair): Int = 8

  override def quoteAssetPrecision(pair: Pair): Int = 8

  override def useFundsForMarketBuys: Boolean = false
}
