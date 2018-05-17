package exchanges

import core.{Exchange, Order, OrderEvent, OrderRequest, Pair, TradingSession}

class GDAX extends Exchange {
  override val makerFee = .0 // nice
  override val takerFee = .3 // This is actually based on your volume, but .3 is worst case

  override def formatPair(pair: Pair): String = s"${pair.base}-${pair.quote}"

  override def cancel(id: String): Unit = ???

  override def update(session: TradingSession): (Seq[Order.Fill], Seq[OrderEvent]) = ???

  override def order(req: OrderRequest): Unit = ???
}
