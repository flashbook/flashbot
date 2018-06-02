package exchanges

import core.{Exchange, MarketData, Order, OrderEvent, OrderRequest, Pair, TradingSession}

class Binance extends Exchange {
  override def makerFee: Double = ???

  override def takerFee: Double = ???

  override def formatPair(pair: Pair): String = ???

  override def update(session: TradingSession, data: MarketData): (Seq[Order.Fill], Seq[OrderEvent]) = ???

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String): Unit = ???
}
