package core

import scala.collection.immutable.{HashMap, Set, TreeMap}

sealed trait Side
case class Sell() extends Side
case class Buy() extends Side

object Side extends Side {
  def parseSide(str: String): Side = str match {
    case "sell" => Sell()
    case "buy" => Buy()
  }
}

case class Order(id: String, price: Double, size: Double, side: Side)

/**
  * The OrderBook processes and aggregates order book events.
  */
case class OrderBook(orders: Map[String, Order] = HashMap.empty,
                     asks: TreeMap[Double, Set[Order]] = TreeMap.empty,
                     bids: TreeMap[Double, Set[Order]] = TreeMap.empty) {

  def isInitialized: Boolean = orders.nonEmpty

  def processOrderEvent(event: OrderEvent): OrderBook = event match {
    case Open(orderId, p, price, size, side) => open(orderId, price, size, side)
    case Done(orderId, p, side, reason, price, remainingSize) => done(orderId)
    case Change(orderId, p, price, newSize) => change(orderId, newSize)
    case _ => this
  }

  def open(id: String, price: Double, size: Double, side: Side): OrderBook = {
    val o = Order(id, price, size, side)
    val newOrders = orders + (id -> o)
    side match {
      case Sell() => copy(
        orders = newOrders,
        asks = addToIndex(asks, o))
      case Buy() => copy(
        orders = newOrders,
        bids = addToIndex(bids, o))
    }
  }

  def done(id: String): OrderBook =
    orders get id match {
      case Some(o@Order(_, _, _, Sell())) => copy(
        orders = orders - id,
        asks = rmFromIndex(asks, o))
      case Some(o@Order(_, _, _, Buy())) => copy(
        orders = orders - id,
        bids = rmFromIndex(bids, o))
      case None => this // Ignore "done" messages for orders not in the book
    }

  def change(id: String, newSize: Double): OrderBook = {
    orders(id) match {
      case o@Order(_, price, _, Sell()) => copy(
        orders = orders + (id -> o.copy(size = newSize)),
        asks = asks + (price -> (asks(price) - o + o.copy(size = newSize))))
      case o@Order(_, price, _, Buy()) => copy(
        orders = orders + (id -> o.copy(size = newSize)),
        bids = bids + (price -> (bids(price) - o + o.copy(size = newSize))))
    }
  }

  private def addToIndex(idx: TreeMap[Double, Set[Order]], o: Order): TreeMap[Double, Set[Order]] =
    idx + (o.price -> (idx.getOrElse(o.price, Set.empty) + o))

  private def rmFromIndex(idx: TreeMap[Double, Set[Order]], o: Order):
  TreeMap[Double, Set[Order]] = {
    val os = idx(o.price) - o
    if (os.isEmpty) idx - o.price else idx + (o.price -> os)
  }
}
