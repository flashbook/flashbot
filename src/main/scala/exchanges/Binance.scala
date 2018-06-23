package exchanges

import core.{Exchange, MarketData, Order, OrderEvent, OrderRequest, Pair, TradingSession}

class Binance extends Exchange {
  override def makerFee: Double = .0005

  override def takerFee: Double = .0005

  override def formatPair(pair: Pair): String = ???

  override def update(session: TradingSession, data: MarketData): (Seq[Order.Fill], Seq[OrderEvent]) = ???

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String): Unit = ???

  override def baseAssetPrecision(pair: Pair): Int = 8

  override def quoteAssetPrecision(pair: Pair): Int = 8
}
