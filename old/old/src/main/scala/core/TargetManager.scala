package core

//import java.util.UUID.randomUUID

//import core.Action.{CancelLimitOrder, PostLimitOrder, PostMarketOrder}
//import core.Order.{Buy, Sell}
//import core.TargetManager.MarketTarget
//import core.TradingSession._

//import scala.math.BigDecimal.RoundingMode

/**
  * TargetManager keeps track of order target ratios and emits order actions. It makes the
  * declarative `orderTargetRatio` strategy interface possible, making strategies easier to write.
  */
//case class TargetManager(markets: Map[Pair, MarketTarget]) {
//  import TargetManager._
//
//  def step(target: OrderTarget, balances: Map[Account, Double])
//    : (TargetManager, Seq[Action]) = target match {
//
//    case OrderTarget(_, ratio, pair, None) =>
//      // Market order target
//      val curr = markets(pair).ratio
//      val diff = ratio - curr
//      // If the difference is not zero, meaning the desired ratio and the current one are not
//      // identical, then we need to place a market order.
//      if (diff != 0)
//        (withMarketRatio(pair, ratio), List(PostMarketOrder(randomUUID.toString,
//          target.id, pair, if (diff > 0) Buy else Sell, Math.abs(diff) / 2)))
//      else (this, List())
//
//    case OrderTarget(_, ratio, pair, Some((_, price))) =>
//      // Limit order target
//      (markets(pair).book.get(target.id), (price, ratio)) match {
//        case (None, (_, .0)) =>
//          // If there is no current limit target, and the next one's ratio is 0, ignore
//          (this, List.empty)
//
//        case (None, (_, _)) =>
//          // In every other case where there is no current limit target, post a limit order.
//          (withLT(pair, target.id, price, ratio),
//            List(postLimitOrderAction(target.id, pair, price, ratio)))
//
//        case (Some(LimitTarget(_, _)), (_, .0)) =>
//          // If there is a current limit target, but the new one has a 0 ratio,
//          // then cancel the limit order.
//          (withoutLT(pair, target.id),
//            List(CancelLimitOrder(randomUUID.toString, target.id, pair)))
//
//        case (Some(LimitTarget(currPrice, currRatio)), (_, _)) =>
//          // Otherwise we have both a current limit target and a new non-zero one.
//          if (currPrice == price && currRatio == ratio)
//            // If all is the same, ignore
//            (this, List.empty)
//          else
//            // Otherwise cancel the current limit order and post a new one after that.
//            (withLT(pair, target.id, price, ratio), List(
//              CancelLimitOrder(randomUUID.toString, target.id, pair),
//              postLimitOrderAction(target.id, pair, price, ratio)))
//      }
//  }
//
//  def withLT(pair: Pair, id: String, price: Double, ratio: Ratio): TargetManager = {
//    val mt = markets(pair)
//    copy(markets = markets + (pair -> mt.copy(book = mt.book + (id -> LimitTarget(price, ratio)))))
//  }
//
//  def withoutLT(pair: Pair, id: String): TargetManager = {
//    val mt = markets(pair)
//    copy(markets = markets + (pair -> mt.copy(book = mt.book - id)))
//  }
//
//  def withMarketRatio(pair: Pair, ratio: core.Ratio): TargetManager = {
//    val mt = markets(pair)
//    copy(markets = markets + (pair -> mt.copy(ratio = ratio)))
//  }
//}
//
//object TargetManager {
//  case class LimitTarget(price: Double, ratio: Double)
//  case class MarketTarget(ratio: Ratio, book: Map[String, LimitTarget] = Map.empty)
//
//  def postLimitOrderAction(id: String, pair: Pair, price: Double, ratio: Ratio): PostLimitOrder =
//    PostLimitOrder(randomUUID.toString, id, pair, if (ratio > 0) Buy else Sell,
//      Math.abs(ratio), price)
//
//  def fromInitialMarketBalances(balances: Map[Account, Double],
//                                markets: Set[Pair]): TargetManager = {
//    val bs = balances.map { case (k, v) => (k.currency, v) }
//    def bal(name: String) = bs.getOrElse(name, 0)
//    TargetManager(markets.toList.map {
//      case p @ Pair(base, quote) => (p,
//        MarketTarget((bal(base), bal(quote)) match {
//          case (0, 0) => 0
//          case (_, 0) => 1
//          case (0, _) => -1
//        }))
//    }.toMap)
//  }
//}
