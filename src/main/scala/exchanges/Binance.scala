package exchanges

import core.{Exchange, Pair}

class Binance extends Exchange {
  override def makerFee: Double = ???

  override def takerFee: Double = ???

  override def formatPair(pair: Pair): String = ???
}
