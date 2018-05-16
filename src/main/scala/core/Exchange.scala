package core

import core.Order.Fill

trait Exchange {
  def makerFee: Double
  def takerFee: Double
  def formatPair(pair: Pair): String
  def update(session: TradingSession): (Seq[Fill], Seq[OrderEvent])
}

object Exchange {
  final case class ExchangeConfig(`class`: String)
}
