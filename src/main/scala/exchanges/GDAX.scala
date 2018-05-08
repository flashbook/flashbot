package exchanges

import core.{Exchange, Pair}

class GDAX extends Exchange {
  override val makerFee = .0 // nice
  override val takerFee = .3 // This is actually based on your volume, but .3 is worst case

  override def formatPair(pair: Pair): String = s"${pair.base}-${pair.quote}"
}
