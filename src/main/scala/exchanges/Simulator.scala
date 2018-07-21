package exchanges

import core.AggBook.{AggBook, AggBookMD, aggFillOrder}
import core.Order.{Buy, Fill, Market, Taker}
import core.OrderBook.OrderBookMD
import core._

import scala.collection.immutable.Queue

/**
  * The simulator is an exchange used for backtesting and paper trading. It takes an instance of a
  * real exchange as a parameter to use as a base implementation, but it simulates all API
  * interactions so that no network requests are actually made.
  */
class Simulator(base: Exchange, latencyMicros: Long) extends Exchange {

  private var currentTimeMicros: Long = 0

  sealed trait APIRequest {
    def requestTime: Long
  }
  case class OrderReq(requestTime: Long, req: OrderRequest) extends APIRequest
  case class CancelReq(requestTime: Long, id: String) extends APIRequest

  private var apiRequestQueue = Queue.empty[APIRequest]

  private var myOrders = Map.empty[Pair, OrderBook]

  private var depths = Map.empty[Pair, AggBook]
  private var prices = Map.empty[Pair, Double]

  override def makerFee: Double = base.makerFee
  override def takerFee: Double = base.takerFee
  override def formatPair(pair: Pair): String = base.formatPair(pair)

  override def collect(session: TradingSession,
                       data: Option[MarketData]): (Seq[Order.Fill], Seq[OrderEvent]) = {
    var fills = Seq.empty[Order.Fill]
    var events = Seq.empty[OrderEvent]

    // Update the current time
    if (data.get.micros > currentTimeMicros) {
      currentTimeMicros = data.get.micros
    }

    // Dequeue and process API requests that have passed the latency threshold
    while (apiRequestQueue.headOption.exists(_.requestTime + latencyMicros < currentTimeMicros)) {
      apiRequestQueue.dequeue match {
        case (r: APIRequest, rest) =>
          val evTime = r.requestTime + latencyMicros
          r match {
            case OrderReq(requestTime, req) => req match {
              /**
                * Limit orders may be filled (fully or partially) immediately. If not immediately
                * fully filled, the remainder is placed on the resting order book.
                */
              case LimitOrderRequest(clientOid, side, product, price, size) =>
                // TODO: Implement this
                ???

              /**
                * Market orders need to be filled immediately.
                */
              case MarketOrderRequest(clientOid, side, product, size, funds) =>
                if (depths.isDefinedAt(product)) {
                  fills = fills ++
                    aggFillOrder(depths(product), side, size, funds.map(_ * (1 - takerFee)))
                      .map { case (price, quantity) => Fill(clientOid, Some(clientOid), takerFee,
                        product, price, quantity, evTime, Taker, side)}

                } else if (prices.isDefinedAt(product)) {
//                  println(size, funds)
                  // We may not have aggregate book data, in that case, simply use the last price.
                  fills = fills :+ Fill(
                    clientOid, Some(clientOid), takerFee, product, prices(product),
                    if (side == Buy) size.getOrElse(funds.get * (1 - takerFee) / prices(product))
                    else size.get,
                    evTime, Taker, side
                  )

                } else {
                  throw new RuntimeException(s"No pricing $product data available for simulation")
                }

                events = events :+
                  OrderReceived(clientOid, product, Some(clientOid), Market) :+
                  OrderDone(clientOid, product, side, Filled, None, None)
            }

            /**
              * Removes the identified order from the resting order book.
              */
            case CancelReq(_, id) =>
              myOrders.foreach {
                case (product, book) if book.orders.contains(id) =>
                  val order = book.orders(id)
                  myOrders = myOrders + (product -> book.done(id))
                  events = events :+ OrderDone(id, product, order.side, Canceled,
                    order.price, Some(order.amount))
                case _ =>
              }
          }
          apiRequestQueue = rest
      }
    }


    // Update latest depth/pricing data
    data match {
      case md: OrderBookMD[_] =>
        // TODO: Turn aggregate full order books into aggregate depths here
        ???
      case md: AggBookMD =>
        depths = depths + (md.product -> md.data)
      case md: Priced =>
        prices = prices + (md.product -> md.price)
      case _ =>
    }

    (fills, events)
  }

  override def order(req: OrderRequest): Unit = {
    apiRequestQueue = apiRequestQueue.enqueue(OrderReq(currentTimeMicros, req))
  }

  override def cancel(id: String): Unit = {
    apiRequestQueue = apiRequestQueue.enqueue(CancelReq(currentTimeMicros, id))
  }

  override def baseAssetPrecision(pair: Pair): Int = base.baseAssetPrecision(pair)

  override def quoteAssetPrecision(pair: Pair): Int = base.quoteAssetPrecision(pair)

  override def useFundsForMarketBuys: Boolean = base.useFundsForMarketBuys

  override def lotSize(pair: Pair): Double = base.lotSize(pair)
}
