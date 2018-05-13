package core

import java.util.UUID.randomUUID

import core.Order.{Buy, Sell}
import core.TradingSession._

/**
  * TargetManager keeps track of order target ratios and emits order actions. It makes the
  * declarative `orderTargetRatio` strategy interface possible, making strategies easier to write.
  */
class TargetManager {
  import TargetManager._

  var markets = Map.empty[Pair, MarketTarget]

  def step(target: OrderTarget): Seq[OrderAction] = target match {
    case OrderTarget(_, ratio, pair, None) =>
      // Market order target
      val curr = markets.getOrElse(pair, MarketTarget()).ratio
      val diff = ratio - curr
      if (diff != 0)
        List(PostMarketOrder(randomUUID.toString, target.id, pair,
          if (diff > 0) Buy else Sell, Math.abs(diff)))
      else List()

    case OrderTarget(_, ratio, pair, Some((_, price))) =>
      // Limit order target
      var actions = List.empty[OrderAction]
      val currMT = markets.getOrElse(pair, MarketTarget())
      (markets, actions) = (currMT.book.get(target.id), (price, ratio)) match {
        case (None, (_, .0)) =>
          // If there is no current limit target, and the next one's ratio is 0, ignore
          (markets, List.empty)

        case (None, (_, _)) =>
          // In every other case where there is no current limit target, post a limit order.
          (withLT(markets, pair, target.id, price, ratio),
            List(postLimitOrderAction(target.id, pair, price, ratio)))

        case (Some(LimitTarget(_, _)), (_, .0)) =>
          // If there is a current limit target, but the new one has a 0 ratio,
          // then cancel the limit order.
          (withoutLT(markets, pair, target.id),
            List(CancelLimitOrder(randomUUID.toString, target.id, pair)))

        case (Some(LimitTarget(currPrice, currRatio)), (_, _)) =>
          // Otherwise we have both a current limit target and a new non-zero one.
          if (currPrice == price && currRatio == ratio)
            // If all is the same, ignore
            (markets, List.empty)
          else
            // Otherwise cancel the current limit order and post a new one after that.
            (withLT(markets, pair, target.id, price, ratio), List(
              CancelLimitOrder(randomUUID.toString, target.id, pair),
              postLimitOrderAction(target.id, pair, price, ratio)))
      }
      actions
  }
}

object TargetManager {
  case class LimitTarget(price: Double, ratio: Double)
  case class MarketTarget(ratio: Ratio = 0, book: Map[String, LimitTarget] = Map.empty)

  def withLT(markets: Map[Pair, MarketTarget], pair: Pair, id: String,
             price: Double, ratio: Ratio): Map[Pair, MarketTarget] = {
    val mt = markets.getOrElse(pair, MarketTarget())
    markets + (pair -> mt.copy(book = mt.book + (id -> LimitTarget(price, ratio))))
  }

  def withoutLT(markets: Map[Pair, MarketTarget], pair: Pair,
                id: String): Map[Pair, MarketTarget] = {
    val mt = markets.getOrElse(pair, MarketTarget())
    markets + (pair -> mt.copy(book = mt.book - id))
  }

  def postLimitOrderAction(id: String, pair: Pair, price: Double, ratio: Ratio): PostLimitOrder =
    PostLimitOrder(randomUUID.toString, id, pair, if (ratio > 0) Buy else Sell,
      Math.abs(ratio), price)
}
