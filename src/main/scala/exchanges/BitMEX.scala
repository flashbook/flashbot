package exchanges

import core.{Exchange, MarketData, Order, OrderEvent, OrderRequest, Pair, TradingSession}

class BitMEX extends Exchange {
  override def makerFee: Double = ???

  override def takerFee: Double = ???

  override def formatPair(pair: Pair): String = ???

  override def cancel(id: String): Unit = ???

  override def update(session: TradingSession, data: MarketData): (Seq[Order.Fill], Seq[OrderEvent]) = ???

  override def order(req: OrderRequest): Unit = ???

  override def baseAssetPrecision(pair: Pair): Int = ???

  override def quoteAssetPrecision(pair: Pair): Int = ???
}
