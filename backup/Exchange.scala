package core

import core.Client.GDAXClient
import io.circe.Printer

case class Trade(id: Long,
                 time: Long,
                 size: Double,
                 price: Double,
                 side: Side)

sealed trait Liquidity
case object Maker extends Liquidity
case object Taker extends Liquidity

case class Fill(orderId: String, tradeId: Long, fee: Double, product: (String, String),
                price: Double, size: Double, createdAt: Long, liquidity: Liquidity, side: Side)

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

final case class Account(currency: String, balance: Double)

final case class PlaceOrderResponse()

/**
  * Exchanges accept order requests and emits ExchangeEvents in response to MarketState updates.
  */
trait Exchange {
  val id: String

  // Initial account values this exchange starts with
  val accounts: Map[String, Double]

  // Orders submitted to the exchange are fire-and-forget
  def order(req: OrderRequest): Unit

  def cancel(id: String): Unit

  // Trades are emitted from the exchange in response to market state updates
  def update(ms: MarketState): (Seq[Fill], Seq[OrderEvent])
}

class GDAX(accs: Map[String, Double], client: GDAXClient) extends Exchange {

  var clientOids: Set[String] = Set.empty
  var ids: Set[String] = Set.empty

  override val id: String = "gdax"

  override def order(req: OrderRequest): Unit = {
    // Record the clientOid so that we can identify our own orders in the feed.
    clientOids = clientOids + req.clientOid

    // Send the HTTP request
  }

  override def cancel(id: String): Unit = {
  }

  override def update(ms: MarketState): (Seq[Fill], Seq[OrderEvent]) = {
    ms.event match {
      case ev@Received(orderId, _, clientOid, _) =>
        if (clientOid.isDefined && clientOids.contains(clientOid.get)) {
          // We just detected that a Received event came in for an order that we placed through
          // this exchange instance. Clean up the clientOid from the set of pending orders, and
          // add the actual orderId to the set of tracked order ids.
          clientOids = clientOids - clientOid.get
          ids = ids + orderId
          (List(), List(ev))
        } else
          (List(), List())

      case ev@Open(orderId, _, _, _, _) =>
        if (ids contains orderId)
          (List(), List(ev))
        else
          (List(), List())

      case ev@Done(orderId, _, _, _, _, _) =>
        if (ids contains orderId) {
          // It's impossible to receive events for this orderId after the Done msg,
          // so we clean up the orderId from the set of tracked orders.
          ids = ids - orderId
          (List(), List(ev))
        } else
          (List(), List())

      case Match(tradeId, product, time, size, price, side, makerOrderId, orderId) =>
        ((ids contains orderId, ids contains makerOrderId) match {
          case (true, false) =>
            // Our market order just matched
            List(Fill(orderId, tradeId, 0.00025, product, price, size, time, Taker, side))
          case (false, true) =>
            // Our limit order just matched
            List(Fill(makerOrderId, tradeId, 0, product, price, size, time, Maker, side))
          case (false, false) =>
            // Ignore if neither id is known to us.
            // Also, this match block errors if both ids exist, since it should be
            // impossible due to self-trade prevention.
            List()
        }, List())

      case Change(orderId, product, price, newSize) =>
        (List(), List()) // ignore
    }
  }

  // TODO: GDAX can fetch accounts instead of being hard coded
  override val accounts: Map[String, Double] = accs
}

class Simulator(accs: Map[String, Double], latency: Long) extends Exchange {
  override val id: String = "simulator"

  override def order(req: OrderRequest): Unit = {
  }

  override def update(ms: MarketState): (Seq[Fill], Seq[OrderEvent]) = {
    None
  }

  override val accounts: Map[String, Double] = accs
}
