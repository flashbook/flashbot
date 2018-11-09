package io.flashbook.flashbot.exchanges

import io.flashbook.flashbot.core.AggBook.{AggBook, AggBookMD, aggFillOrder}
import io.flashbook.flashbot.core.Order
import io.flashbook.flashbot.core.Order._
import io.flashbook.flashbot.core.OrderBook.OrderBookMD
import io.flashbook.flashbot.core._

import scala.collection.immutable.Queue

/**
  * The simulator is an exchange used for backtesting and paper trading. It takes an instance of a
  * real exchange as a parameter to use as a base implementation, but it simulates all API
  * interactions so that no network requests are actually made.
  */
class Simulator(base: Exchange, latencyMicros: Long = 0) extends Exchange {

  private var currentTimeMicros: Long = 0

  sealed trait APIRequest {
    def requestTime: Long
  }
  case class OrderReq(requestTime: Long, req: OrderRequest) extends APIRequest
  case class CancelReq(requestTime: Long, id: String, pair: Pair) extends APIRequest

  private var apiRequestQueue = Queue.empty[APIRequest]

  private var myOrders = Map.empty[Pair, OrderBook]

  private var depths = Map.empty[Pair, AggBook]
  private var prices = Map.empty[Pair, Double]

  override def makerFee: Double = base.makerFee
  override def takerFee: Double = base.takerFee

  override def collect(session: TradingSession,
                       data: Option[MarketData]): (Seq[Order.Fill], Seq[OrderEvent]) = {
    var fills = Seq.empty[Order.Fill]
    var events = Seq.empty[OrderEvent]

    // Update the current time, based on the time of the incoming market data.
    currentTimeMicros = math.max(data.map(_.micros).getOrElse(0L), currentTimeMicros)

    // Dequeue and process API requests that have passed the latency threshold
    while (apiRequestQueue.headOption
        .exists(_.requestTime + latencyMicros <= currentTimeMicros)) {
      apiRequestQueue.dequeue match {
        case (r: APIRequest, rest) =>
          val evTime = r.requestTime + latencyMicros
          r match {
            case OrderReq(requestTime, req) => req match {
              /**
                * Limit orders may be filled (fully or partially) immediately. If not immediately
                * fully filled, the remainder is placed on the resting order book.
                */
              case LimitOrderRequest(clientOid, side, product, size, price, postOnly) =>
                if (!depths.isDefinedAt(product)) {
                  throw new RuntimeException("Aggregate order books are required to simulate " +
                    "limit orders.")
                }

                val immediateFills =
                  aggFillOrder(depths(product), side, Some(size), None, Some(price))
                  .map { case (fillPrice, fillQuantity) =>
                    Fill(clientOid, Some(clientOid), takerFee, product, fillPrice, fillQuantity,
                      evTime, Taker, side)
                  }
                fills ++= immediateFills

                events :+= OrderReceived(clientOid, product, Some(clientOid), Order.Limit)

                // Either complete or open the limit order
                val remainingSize = size - immediateFills.map(_.size).sum
                if (remainingSize > 0) {
                  myOrders = myOrders + (product ->
                    myOrders.getOrElse(product, OrderBook())
                      .open(clientOid, price, remainingSize, side))
                  events :+= OrderOpen(clientOid, product, price, remainingSize, side)
                } else {
                  events :+= OrderDone(clientOid, product, side, Filled, Some(price), Some(0))
                }

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
                  OrderReceived(clientOid, product, Some(clientOid), Order.Market) :+
                  OrderDone(clientOid, product, side, Filled, None, None)
            }

            /**
              * Removes the identified order from the resting order book.
              */
            case CancelReq(_, id, pair) =>
              val order = myOrders(pair).orders(id)
              myOrders = myOrders + (pair -> myOrders(pair).done(id))
              events = events :+ OrderDone(id, pair, order.side, Canceled,
                order.price, Some(order.amount))
          }
          apiRequestQueue = rest
      }
    }

    // Update latest depth/pricing data.
    data match {
      case Some(md: OrderBookMD[_]) =>
        // TODO: Turn aggregate full order books into aggregate depths here
        ???
      case Some(md: AggBookMD) =>
        depths = depths + (md.product -> md.data)
      case Some(md: Priced) =>
        prices = prices + (md.product -> md.price)

      /**
        * Match trades against the aggregate book. This is a pretty naive matching strategy.
        * We should use a more precise matching engine for full order books. Also, this mutates
        * our book depths until they are overwritten by a more recent depth snapshot.
        */
      case Some(md: TradeMD) if depths.isDefinedAt(md.product) =>
        // First simulate fills on the aggregate book. Remove the associated liquidity from
        // the depths.
        val simulatedFills =
          aggFillOrder(depths(md.product), md.data.side, Some(md.data.size), None)
        simulatedFills.foreach { case (fillPrice, fillAmount) =>
            depths = depths + (md.product -> depths(md.product).updateLevel(
              md.data.side match {
                case Buy => Ask
                case Sell => Bid
              }, fillPrice, depths(md.product).quantityAtPrice(fillPrice).get - fillAmount
            ))
        }

        // Then use the fills to determine if any of our orders would have executed.
        if (myOrders.isDefinedAt(md.product)) {
          val lastFillPrice = simulatedFills.last._1
          val filledOrders = md.data.side match {
            case Buy =>
              myOrders(md.product).asks.filter(_._1 < lastFillPrice).values.toSet.flatten
            case Sell =>
              myOrders(md.product).bids.filter(_._1 > lastFillPrice).values.toSet.flatten
          }
          filledOrders.foreach { order =>
            // Remove order from private book
            myOrders = myOrders + (md.product -> myOrders(md.product).done(order.id))

            // Emit OrderDone event
            events :+= OrderDone(order.id, md.product, order.side, Filled, order.price, Some(0))

            // Emit the fill
            println("SIMULATED FILL!!!!!!!!!!!!")
            fills :+= Fill(order.id, Some(md.data.id), makerFee, md.product, order.price.get,
              order.amount, md.micros, Maker, order.side)
          }
        }
      case _ =>
    }

    (fills, events)
  }

  override def order(req: OrderRequest): Unit = {
    apiRequestQueue = apiRequestQueue.enqueue(OrderReq(currentTimeMicros, req))
    tick()
  }

  override def cancel(id: String, pair: Pair): Unit = {
    apiRequestQueue = apiRequestQueue.enqueue(CancelReq(currentTimeMicros, id, pair))
    tick()
  }

  override def baseAssetPrecision(pair: Pair): Int = base.baseAssetPrecision(pair)

  override def quoteAssetPrecision(pair: Pair): Int = base.quoteAssetPrecision(pair)

  override def useFundsForMarketBuys: Boolean = base.useFundsForMarketBuys

  override def lotSize(pair: Pair): Option[Double] = base.lotSize(pair)
}
