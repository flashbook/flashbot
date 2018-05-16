package core

import core.Order._

object Order {
  trait Side
  case object Buy extends Side
  case object Sell extends Side

  trait Liquidity
  case object Maker extends Liquidity
  case object Taker extends Liquidity
}

case class Order(id: String,
                 pair: Pair,
                 side: Side,
                 amount: Double,
                 price: Option[Double])
