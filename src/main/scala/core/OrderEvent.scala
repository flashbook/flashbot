package core

import core.MarketData.{Sequenced}
import core.Order.{Limit, Market, OrderType, Side}

sealed trait OrderEvent {
  val orderId: String
  val product: Pair
}

final case class Open(orderId: String,
                      product: Pair,
                      price: Double,
                      size: Double,
                      side: Side) extends OrderEvent

final case class Done(orderId: String,
                      product: Pair,
                      side: Side,
                      reason: DoneReason,
                      price: Option[Double],
                      remainingSize: Option[Double]) extends OrderEvent {
  def orderType: OrderType = (price, remainingSize) match {
    case (Some(_), Some(_)) => Limit
    case (None, None) => Market
  }
}

final case class Change(orderId: String,
                        product: Pair,
                        price: Option[Double],
                        newSize: Double) extends OrderEvent {
  def orderType: OrderType = if (price.isDefined) Limit else Market
}

final case class Match(tradeId: Long,
                       product: Pair,
                       micros: Long,
                       size: Double,
                       price: Double,
                       side: Side,
                       makerOrderId: String,
                       orderId: String) extends OrderEvent

final case class Received(orderId: String,
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
