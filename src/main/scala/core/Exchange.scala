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

  /**
    * A function that returns user data by the exchange in its current state for the given
    * trading session.
    */
  def update(session: TradingSession, data: MarketData): (Seq[Fill], Seq[OrderEvent])

  // API requests submitted to the exchange are fire-and-forget, hence the Unit return type
  def order(req: OrderRequest): Unit
  def cancel(id: String): Unit
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

