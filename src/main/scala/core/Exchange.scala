package core

import core.Order.{Fill, Side}

object Exchange {
  final case class ExchangeConfig(`class`: String)
}

trait Exchange {
  def makerFee: Double
  def takerFee: Double
  def formatPair(pair: Pair): String

  def cancel(id: String): Unit
  def update(session: TradingSession): (Seq[Fill], Seq[OrderEvent])

  // Orders submitted to the exchange are fire-and-forget
  def order(req: OrderRequest): Unit
}

sealed trait OrderRequest {
  val clientOid: String
  val side: Side
  val product: (String, String)
}
final case class LimitOrderRequest(clientOid: String,
                                   side: Side,
                                   product: (String, String),
                                   price: Double,
                                   size: Double) extends OrderRequest
final case class MarketOrderRequest(clientOid: String,
                                    side: Side,
                                    product: (String, String),
                                    size: Option[Double],
                                    funds: Option[Double]) extends OrderRequest

