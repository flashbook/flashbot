package exchanges

import core.{Exchange, Pair}

class BitMEX extends Exchange {
  override def makerFee: Double = ???

  override def takerFee: Double = ???

  override def formatPair(pair: Pair): String = ???
}
