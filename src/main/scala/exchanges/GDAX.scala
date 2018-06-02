package exchanges

import core.{Exchange, MarketData, Order, OrderEvent, OrderRequest, Pair, TradingSession}

class GDAX extends Exchange {
  override val makerFee = .0 // nice
  override val takerFee = .003 // This is actually based on your volume, but .3 is worst case

  override def formatPair(pair: Pair): String = s"${pair.base}-${pair.quote}"

  override def update(session: TradingSession,
                      data: MarketData): (Seq[Order.Fill], Seq[OrderEvent]) = ???

  override def order(req: OrderRequest): Unit = ???

  override def cancel(id: String): Unit = ???
}
