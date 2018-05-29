package core

import core.Order._

object Order {
  trait Side
  case object Buy extends Side
  case object Sell extends Side

  object Side extends Side {
    def parseSide(str: String): Side = str match {
      case "sell" => Sell
      case "buy" => Buy
    }
  }


  trait Liquidity
  case object Maker extends Liquidity
  case object Taker extends Liquidity

  sealed trait OrderType
  case object Market extends OrderType
  case object Limit extends OrderType

  object OrderType {
    def parseOrderType(str: String): OrderType = str match {
      case "market" => Market
      case "limit" => Limit
    }
  }

  case class Fill(orderId: String, tradeId: String, fee: Double, pair: Pair,
                  price: Double, size: Double, createdAt: Long, liquidity: Liquidity, side: Side)
}

case class Order(id: String,
                 side: Side,
                 amount: Double,
                 price: Option[Double])
