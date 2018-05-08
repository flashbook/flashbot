package core

trait Exchange {
  def makerFee: Double
  def takerFee: Double

  def formatPair(pair: Pair): String
}

object Exchange {
  final case class ExchangeConfig(`class`: String)
}
