package core

import core.Order.{Fill, Side}
import io.circe.generic.auto._

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
  val product: Pair
}
final case class LimitOrderRequest(clientOid: String,
                                   side: Side,
                                   product: Pair,
                                   price: Double,
                                   size: Double) extends OrderRequest
final case class MarketOrderRequest(clientOid: String,
                                    side: Side,
                                    product: Pair,
                                    size: Option[Double],
                                    funds: Option[Double]) extends OrderRequest

