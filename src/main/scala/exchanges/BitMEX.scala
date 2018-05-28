package exchanges

import core.{Exchange, Order, OrderEvent, OrderRequest, Pair, TradingSession}

class BitMEX extends Exchange {
  override def makerFee: Double = ???

  override def takerFee: Double = ???

  override def formatPair(pair: Pair): String = ???

  override def cancel(id: String): Unit = ???

  override def update(session: TradingSession): (Seq[Order.Fill], Seq[OrderEvent]) = ???

  override def order(req: OrderRequest): Unit = ???
}
