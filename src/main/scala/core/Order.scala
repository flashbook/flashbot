package core

import core.Order._

object Order {
  trait Side
  case object Buy extends Side
  case object Sell extends Side
}

case class Order(id: String, side: Side, amount: Double, price: Option[Double])
