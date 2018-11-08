package core

import utils.Utils._

sealed trait OrderEvent {
  val orderId: String
  val product: (String, String)
}

final case class Open(orderId: String,
                      product: (String, String),
                      price: Double,
                      size: Double,
                      side: Side) extends OrderEvent

final case class Done(orderId: String,
                      product: (String, String),
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
                        product: (String, String),
                        price: Option[Double],
                        newSize: Double) extends OrderEvent {
  def orderType: OrderType = if (price.isDefined) Limit else Market
}

final case class Match(tradeId: Long,
                       product: (String, String),
                       time: Long,
                       size: Double,
                       price: Double,
                       side: Side,
                       makerOrderId: String,
                       orderId: String) extends OrderEvent

final case class Received(orderId: String,
                          product: (String, String),
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

sealed trait OrderType
case object Market extends OrderType
case object Limit extends OrderType

object OrderType {
  def parseOrderType(str: String): OrderType = str match {
    case "market" => Market
    case "limit" => Limit
  }
}

// How we receive order events from the API. Fields are strings for some reason.
case class UnparsedAPIOrderEvent(`type`: String,
                                 product_id: String,
                                 sequence: Option[Long],
                                 time: Option[String],
                                 size: Option[String],
                                 price: Option[String],
                                 order_id: Option[String],
                                 side: Option[String],
                                 reason: Option[String],
                                 order_type: Option[String],
                                 remaining_size: Option[String],
                                 funds: Option[String],
                                 trade_id: Option[Long],
                                 maker_order_id: Option[String],
                                 taker_order_id: Option[String],
                                 taker_user_id: Option[String],
                                 user_id: Option[String],
                                 taker_profile_id: Option[String],
                                 profile_id: Option[String],
                                 new_size: Option[String],
                                 old_size: Option[String],
                                 new_funds: Option[String],
                                 old_funds: Option[String],
                                 last_size: Option[String],
                                 best_bid: Option[String],
                                 best_ask: Option[String],
                                 client_oid: Option[String]) {

  def parse: APIOrderEvent = APIOrderEvent(`type`, product_id, sequence,
    time.map(ISO8601ToMicros), size.map(_.toDouble), price.map(_.toDouble), order_id,
    side, reason, order_type, remaining_size.map(_.toDouble), funds.map(_.toDouble), trade_id,
    maker_order_id, taker_order_id, taker_user_id, user_id, taker_profile_id, profile_id,
    new_size.map(_.toDouble), old_size.map(_.toDouble), new_funds.map(_.toDouble),
    old_funds.map(_.toDouble), last_size.map(_.toDouble), best_bid.map(_.toDouble),
    best_ask.map(_.toDouble), client_oid)
}

// How we store API events in memory and disk.
case class APIOrderEvent(`type`: String,
                          product_id: String,
                          sequence: Option[Long],
                          time: Option[Long],
                          size: Option[Double],
                          price: Option[Double],
                          order_id: Option[String],
                          side: Option[String],
                          reason: Option[String],
                          order_type: Option[String],
                          remaining_size: Option[Double],
                          funds: Option[Double],
                          trade_id: Option[Long],
                          maker_order_id: Option[String],
                          taker_order_id: Option[String],
                          taker_user_id: Option[String],
                          user_id: Option[String],
                          taker_profile_id: Option[String],
                          profile_id: Option[String],
                          new_size: Option[Double],
                          old_size: Option[Double],
                          new_funds: Option[Double],
                          old_funds: Option[Double],
                          last_size: Option[Double],
                          best_bid: Option[Double],
                          best_ask: Option[Double],
                          client_oid: Option[String]) extends Ordered[APIOrderEvent] {

  override def compare(that: APIOrderEvent): Int = {
    if (sequence == that.sequence) 0
    else if (sequence.get > that.sequence.get) 1
    else -1
  }

  def toOrderEvent: OrderEvent = {
    `type` match {
      case "open" =>
        Open(order_id.get, parseProductId(product_id), price.get, remaining_size.get, Side.parseSide(side.get))
      case "done" =>
        Done(order_id.get, parseProductId(product_id), Side.parseSide(side.get), DoneReason.parse(reason.get), price, remaining_size)
      case "change" =>
        Change(order_id.get, parseProductId(product_id), price, new_size.get)
      case "match" =>
        Match(trade_id.get, parseProductId(product_id), time.get, size.get, price.get, Side.parseSide(side.get),
          maker_order_id.get, taker_order_id.get)
      case "received" =>
        Received(order_id.get, parseProductId(product_id), client_oid, OrderType.parseOrderType(order_type.get))
    }
  }
}
