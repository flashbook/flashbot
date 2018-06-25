package core

import core.Action.{ActionQueue, PostMarketOrder}
import core.TradingSession.OrderTarget

import scala.collection.immutable.Queue
import java.util.UUID.randomUUID

import core.Order.{Buy, Sell, Side}

import scala.math.BigDecimal.RoundingMode.DOWN

case class OrderManager(targets: Queue[OrderTarget] = Queue.empty) {

  def submitTarget(target: OrderTarget): OrderManager =
    copy(targets = targets.enqueue(target))

  def enqueueActions(exchange: Exchange,
                     currentActions: ActionQueue,
                     balances: Map[String, Double],
                     prices: Map[Pair, Double]): (OrderManager, ActionQueue) = {

    def roundQuote(balance: Double, pair: Pair): Double =
      BigDecimal(balance).setScale(exchange.quoteAssetPrecision(pair), DOWN).doubleValue()
    def roundBase(balance: Double, pair: Pair): Double =
      BigDecimal(balance).setScale(exchange.baseAssetPrecision(pair), DOWN).doubleValue()

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
              *
              * baseBalance * price + quoteBalance = totalFunds
              */
            case OrderTarget(ex, ratio, pair @ Pair(base, quote), None) =>

              val curBaseBalance: Double = balances.getOrElse(base, 0)
              val curQuoteBalance: Double = balances.getOrElse(quote, 0)
              val totalFunds = curBaseBalance * prices(pair) + curQuoteBalance
              val basePercent = (ratio + 1) / 2

              val targetQuoteBalance = (1 - basePercent) * totalFunds
              val targetBaseBalance = (totalFunds - targetQuoteBalance) / prices(pair)

              val rtq = roundQuote(targetQuoteBalance, pair)
              val rtb = roundBase(targetBaseBalance, pair)
              val rcq = roundQuote(curQuoteBalance, pair)
              val rcb = roundBase(curBaseBalance, pair)

              val (side: Option[Side], size: Option[Double], funds: Option[Double]) =
                if (exchange.useFundsForMarketBuys && rtq > rcq)
                  (Some(Buy), None, Some(rtq - rcq))
                else if (rtb > rcb)
                  (Some(Buy), Some(rtb - rcb), None)
                else if (rtb < rcb)
                  (Some(Sell), Some(rcb - rtb), None)
                else
                  (None, None, None)

              if (side.nonEmpty)
                List(PostMarketOrder(randomUUID.toString, target.id, pair, side.get, size, funds))
              else
                Nil

            /**
              * Limit order target.
              */
            case OrderTarget(ex, ratio, pair, Some((targetId, price))) =>
              Nil
          }
          (copy(targets = newTargetQueue), currentActions.enqueue(actions))
      }
    }
  }
}
