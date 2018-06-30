package core

import core.MarketData.{Sequenced}
import core.Order.{Limit, OrderType, Side}

sealed trait OrderEvent extends StrategyEvent {
  val orderId: String
  val product: Pair
}

final case class OrderOpen(orderId: String,
                           product: Pair,
                           price: Double,
                           size: Double,
                           side: Side) extends OrderEvent

final case class OrderDone(orderId: String,
                           product: Pair,
                           side: Side,
                           reason: DoneReason,
                           price: Option[Double],
                           remainingSize: Option[Double]) extends OrderEvent {
  def orderType: OrderType = (price, remainingSize) match {
    case (Some(_), Some(_)) => Limit
    case (None, None) => Order.Market
  }
}

final case class OrderChange(orderId: String,
                             product: Pair,
                             price: Option[Double],
                             newSize: Double) extends OrderEvent {
  def orderType: OrderType = if (price.isDefined) Limit else Order.Market
}

final case class OrderMatch(tradeId: Long,
                            product: Pair,
                            micros: Long,
                            size: Double,
                            price: Double,
                            side: Side,
                            makerOrderId: String,
                            orderId: String) extends OrderEvent

final case class OrderReceived(orderId: String,
                               product: Pair,
                               clientOid: Option[String],
                               `type`: OrderType) extends OrderEvent


sealed trait DoneReason
case object Canceled extends DoneReason
case object Filled extends DoneReason

object DoneReason {
  def parse(str: String): DoneReason = str match {
    case "canceled" => Canceled
    case "filled" => Filled
  }
}

trait RawOrderEvent extends Timestamped with Sequenced {
  def toOrderEvent: OrderEvent
}
