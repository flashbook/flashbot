package core

import core.Order._

object Order {
  trait Side
  case object Buy extends Side
  case object Sell extends Side

  trait Liquidity
  case object Maker extends Liquidity
  case object Taker extends Liquidity

  sealed trait OrderType
  case object Market extends OrderType
  case object Limit extends OrderType

  case class Fill(orderId: String, tradeId: String, fee: Double, pair: Pair,
                  price: Double, size: Double, createdAt: Long, liquidity: Liquidity, side: Side)
}

case class Order(id: String,
                 pair: Pair,
                 side: Side,
                 amount: Double,
                 price: Option[Double])
