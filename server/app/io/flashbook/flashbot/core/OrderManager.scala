package io.flashbook.flashbot.core

import io.flashbook.flashbot.core.Action.{ActionQueue, CancelLimitOrder, PostLimitOrder, PostMarketOrder}
import io.flashbook.flashbot.engine.IdManager
import io.flashbook.flashbot.engine.TradingSession.OrderTarget

import scala.collection.immutable.Queue
import scala.math.BigDecimal.RoundingMode.HALF_DOWN

/**
  * We have one order manager per exchange. It handles order targets received from a strategy
  * and emits actions to be executed by the engine.
  *
  * @param targets a queue of order targets for the exchange
  * @param mountedTargets a map of target ids that represents the theoretical order state
  *                       that should exist on the exchange. The value is the create order
  *                       Action which is associated with this target. For market orders,
  *                       this represents an in-flight orders. For limit orders, this
  *                       represents an in-flight OR resting limit order.
  * @param ids an ID manager for linking orders to target ids
  * @param pegs the configured pegs used for calculating order sizes
  */
case class OrderManager(targets: Queue[OrderTarget] = Queue.empty,
                        mountedTargets: Map[TargetId, Action] = Map.empty,
                        ids: IdManager = IdManager()) {

  def submitTarget(target: OrderTarget): OrderManager =
    copy(targets = targets.enqueue(target))

  def enqueueActions(exchange: Exchange,
                     currentActions: ActionQueue): (OrderManager, ActionQueue) = {

//    def roundQuote(pair: Pair)(balance: Double): Double =
//      BigDecimal(balance).setScale(exchange.quoteAssetPrecision(pair), HALF_DOWN).doubleValue()
//    def roundBase(pair: Pair)(balance: Double): Double =
//      BigDecimal(balance).setScale(exchange.baseAssetPrecision(pair), HALF_DOWN).doubleValue()

//    /**
//      * Shared order size calculation for both limit and market orders. Returns either a valid
//      * non-zero order size or None.
//      */
//    def calculateFixedOrderSize(exchangeName: String, size: Size, pair: Pair,
//                                isMarketOrder: Boolean): Option[FixedSize] = {
//
//      val fixedSizeRaw: FixedSize = size match {
//        /**
//          * Start with looking for fixed sizes, which will be used as order size directly.
//          */
//        case fs: FixedSize => fs
//
//        case Ratio(ratio, extraBaseAssets, basePegs) =>
//          val baseAccount = Account(exchangeName, pair.base)
//          val quoteAccount = Account(exchangeName, pair.quote)
//
//          // Determine the portfolio of accounts that hold our base assets.
//          val explicitBaseAssets = extraBaseAssets + pair.base
//          val peggedBaseAssets = pegs.of(explicitBaseAssets)
//          val baseAssets = portfolio.filter {
//            case (Account(_, asset), _) =>
//              (explicitBaseAssets ++ (if (basePegs) peggedBaseAssets else Set.empty))
//                .contains(asset)
//          }
//
//          // First calculate our existing base position.
//          // This is simply the sum of all base asset balances.
//          val pos = baseAssets.balances.values.sum
//
//          // Then calculate our position bounds. That is, what will our position be if we buy the
//          // maximum amount, and if we sell the maximum amount available for this order.
//          val buymax = ???
//          val sellmin = ???
//
//          // Now we can use the ratio to determine the target position and generate the order.
//
//
////          val hedge = hedges.getOrElse[Double](pair.base, 0)
//
//          // Build the max notional position value for each coin, based on its hedge
//          // Get the min of that collection. The notional value of the current coin
//          // divided by this minimum is the factor by which the ratio needs to be
//          // scaled down by.
//          val min = scopeCoins
//            .map(coin => -hedges.getOrElse[Double](coin, 0) * prices(Pair(coin, pair.quote)))
//            .filter(_ > 0)
//            .min
//          val weight = (-hedge * prices(pair)) / min
//          val weightedRatio = ratio / weight
//
//          val targetBase = -hedge - (weightedRatio * hedge)
//          val currentBase = balances.getOrElse[Double](pair.base, 0)
//          val lotSize = exchange.lotSize(pair)
//          val baseDiff =
//            if (lotSize.isDefined) (((targetBase - currentBase) / lotSize.get).toLong *
//              BigDecimal(lotSize.get)).doubleValue
//            else targetBase - currentBase
//
//          if (baseDiff > 0 && exchange.useFundsForMarketBuys && isMarketOrder) {
//            Funds(baseDiff * prices(pair))
//          } else {
//            Amount(baseDiff)
//          }
//      }
//
//      val fixedSizeRounded = fixedSizeRaw match {
//        case Amount(s) => Amount(roundBase(pair)(s))
//        case Funds(s) => Funds(roundQuote(pair)(s))
//      }
//
//      if (fixedSizeRounded.size == 0) None
//      else Some(fixedSizeRounded)
//    }

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
              */
            case ot @ OrderTarget(ex, targetId, size, None, _) =>
              List(PostMarketOrder(
                exchange.genOrderId,
                targetId,
                size.side,
                size.amount,
                size.funds
              ))

            /**
              * Limit order target.
              *
              * Emit a PostLimitOrder action. Unlike market orders, this action needs to be
              * idempotent. It also needs to clean up after any existing limit orders with
              * the same target id.
              */
            case ot @ OrderTarget(ex, targetId, size, Some(price), postOnly) =>
              val post = PostLimitOrder(
                exchange.genOrderId,
                targetId,
                size.side,
                size.amount.get,
                price,
                postOnly
              )

              val cancel = CancelLimitOrder(targetId)

              mountedTargets.get(targetId) match {
                /**
                  * Existing mounted target is identical to this one. Ignore for idempotency.
                  */
                case Some(action: PostLimitOrder)
                  if action.price == price && action.size == size.amount.get => Nil

                /**
                  * Existing mounted target is different than this target. Cancel the previous
                  * order and create the new one.
                  */
                case Some(_: PostLimitOrder) => List(cancel, post)

                /**
                  * No existing mounted target. Simply post the limit order.
                  */
                case None => List(post)
              }
          }

          // Detect if this target was a no-op, and if there are more targets, recurse.
          // This prevents the target queue from backing up.
          if (newTargetQueue.nonEmpty && actions.isEmpty) {
            copy(targets = newTargetQueue).enqueueActions(exchange, currentActions)
          } else {
            (copy(targets = newTargetQueue), currentActions.enqueue(actions))
          }
      }
    }
  }

  def initCreateOrder(targetId: TargetId, clientId: String, action: Action): OrderManager = {
    if (mountedTargets contains targetId) {
      throw new RuntimeException(s"Order target id $targetId already exists.")
    }

    copy(
      ids = ids.initCreateOrderId(targetId, clientId),
      mountedTargets = mountedTargets + (targetId -> action)
    )
  }

  def initCancelOrder(targetId: TargetId): OrderManager = {
    if (!mountedTargets.contains(targetId)) {
      throw new RuntimeException(s"Cannot cancel unmounted target id: $targetId")
    }

    copy(mountedTargets = mountedTargets - targetId)
  }

  def receivedOrder(clientId: String, actualId: String): OrderManager = {
    copy(ids = ids.receivedOrderId(clientId, actualId))
  }

  def openOrder(event: OrderOpen): OrderManager = {
    copy()
  }

  def orderComplete(actualId: String): OrderManager = {
    copy(
      mountedTargets = mountedTargets - ids.actualToTarget(actualId),
      ids = ids.orderIdComplete(actualId)
    )
  }
}

