package io.flashbook.flashbot.exchanges

import io.flashbook.flashbot.core.{Exchange, MarketData, Order, OrderEvent, OrderRequest, Pair}

class BitMEX extends Exchange {
  override def makerFee: Double = ???

  override def takerFee: Double = ???

  override def cancel(id: String, pair: Pair): Unit = ???

  override def order(req: OrderRequest): Unit = ???

  override def baseAssetPrecision(pair: Pair): Int = ???

  override def quoteAssetPrecision(pair: Pair): Int = ???

  override def useFundsForMarketBuys: Boolean = ???

  override def lotSize(pair: Pair): Option[Double] = ???
}
