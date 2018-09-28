package core

import com.sun.xml.internal.bind.v2.runtime.output.DOMOutput
import core.Action.{ActionQueue, PostLimitOrder, PostMarketOrder}
import core.TradingSession._

import scala.collection.immutable.Queue
import core.Order.{Buy, Sell, Side}

import scala.math.BigDecimal.RoundingMode
import scala.math.BigDecimal.RoundingMode.HALF_DOWN

case class OrderManager(targets: Queue[OrderTarget] = Queue.empty) {

  def submitTarget(target: OrderTarget): OrderManager =
    copy(targets = targets.enqueue(target))

  def enqueueActions(exchange: Exchange,
                     currentActions: ActionQueue,
                     balances: Map[String, Double],
                     prices: Map[Pair, Double],
                     hedges: Map[String, Double]): (OrderManager, ActionQueue) = {

    def roundQuote(pair: Pair)(balance: Double): Double =
      BigDecimal(balance).setScale(exchange.quoteAssetPrecision(pair), HALF_DOWN).doubleValue()
    def roundBase(pair: Pair)(balance: Double): Double =
      BigDecimal(balance).setScale(exchange.baseAssetPrecision(pair), HALF_DOWN).doubleValue()

    /**
      * Shared order size calculation for both limit and market orders. Returns either a valid
      * non-zero order size or None.
      */
    def calculateFixedOrderSize(size: Size, pair: Pair, isMarketOrder: Boolean): Option[FixedSize] = {

      val fixedSizeRaw: FixedSize = size match {
        /**
          * Start with looking for fixed sizes, which will be used as order size directly.
          */
        case fs: FixedSize => fs

        /**
          * Then check if it was a ratio amount, and calculate the correct order size.
          * We want to enforce scopes here as well, so that when we go +1 btc/usd and
          * -1 on eth/usd, we are long and short the same value of usd. As much as the
          * portfolio balances will allow.
          */
        case Ratio(ratio, scope) =>

          // Take all the coins in the scope, including the current base.
          val scopeCoins = ((scope match {
            case Portfolio => balances.keySet ++ hedges.keySet
            case PairScope => Set.empty[String]
            case Basket(coins) => coins
          }) + pair.base) - pair.quote

          val hedge = hedges.getOrElse[Double](pair.base, 0)

          // Build the max notional position value for each coin, based on its hedge
          // Get the min of that collection. The notional value of the current coin
          // divided by this minimum is the factor by which the ratio needs to be
          // scaled down by.
          val min = scopeCoins
            .map(coin => -hedges.getOrElse[Double](coin, 0) * prices(Pair(coin, pair.quote)))
            .filter(_ > 0)
            .min
          val weight = (-hedge * prices(pair)) / min
          val weightedRatio = ratio / weight

          val targetBase = -hedge - (weightedRatio * hedge)
          val currentBase = balances.getOrElse[Double](pair.base, 0)
          val lotSize = exchange.lotSize(pair)
          val baseDiff =
            if (lotSize.isDefined) (((targetBase - currentBase) / lotSize.get).toLong *
              BigDecimal(lotSize.get)).doubleValue
            else targetBase - currentBase

          if (baseDiff > 0 && exchange.useFundsForMarketBuys && isMarketOrder) {
            Funds(baseDiff * prices(pair))
          } else {
            Amount(baseDiff)
          }
      }

      val fixedSizeRounded = fixedSizeRaw match {
        case Amount(s) => Amount(roundBase(pair)(s))
        case Funds(s) => Funds(roundQuote(pair)(s))
      }

      if (fixedSizeRounded.size == 0) None
      else Some(fixedSizeRounded)
    }

    if (currentActions.nonEmpty) {
      // If the current actions queue still has actions to work with, then don't do anything.
      (this, currentActions)

    } else if (targets.isEmpty) {
      // If both the actions and the targets queues are empty, there is again nothing to do.
      (this, currentActions)

    } else {
      // Otherwise if the current actions queue is empty, then populate it with the result of
      // expanding the next order target.
      targets.dequeue match {
        case (target, newTargetQueue) =>
          val actions = target match {

            /**
              * Market order target.
              * Calculate the fixed order size and emit a PostMarketOrder action.
              */
            case ot @ OrderTarget(ex, size, pair, None) =>
              calculateFixedOrderSize(size, pair, isMarketOrder = true) match {
                case Some(fixedSize) =>
                  List(PostMarketOrder(
                    exchange.genOrderId, target.id, pair,
                    fixedSize.side,
                    fixedSize.amount,
                    fixedSize.funds
                  ))
                case None =>
                  println("WARNING: Dropping empty market order", ot)
                  Nil
              }


            /**
              * Limit order target.
              * Similarly to market orders, calculate the fixed order size and emit a
              * PostMarketOrder action. Unlike market orders, this action needs to be
              * idempotent. It also needs to clean up after any existing limit orders
              * with the same target id.
              */
            case ot @ OrderTarget(ex, size, pair, Some((name, price))) =>
              calculateFixedOrderSize(size, pair, isMarketOrder = false) match {
                case Some(fixedSize) =>
                  val post = PostLimitOrder(
                    exchange.genOrderId, name, pair,
                    fixedSize.side,
                    fixedSize.amount.get,
                    price
                  )

                case None =>
                  println("WARNING: Dropping empty limit order", ot)
                  Nil
              }
          }
          (copy(targets = newTargetQueue), currentActions.enqueue(actions))
      }
    }
  }
}
