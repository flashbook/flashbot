package core

import core.MarketData.{GenMD, HasProduct, Sequenced, Timestamped}
import core.Order.{Buy, Sell, Side}
import core.Utils.parseProductId

import scala.collection.immutable.TreeMap

case class OrderBook(orders: Map[String, Order] = Map.empty,
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
    val o = Order(id, side, size, Some(price))
    val newOrders = orders + (id -> o)
    side match {
      case Sell => copy(
        orders = newOrders,
        asks = addToIndex(asks, o))
      case Buy => copy(
        orders = newOrders,
        bids = addToIndex(bids, o))
    }
  }

  def done(id: String): OrderBook =
    orders get id match {
      case Some(o@Order(_, Sell, _, _)) => copy(
        orders = orders - id,
        asks = rmFromIndex(asks, o))
      case Some(o@Order(_, Buy, _, _)) => copy(
        orders = orders - id,
        bids = rmFromIndex(bids, o))
      case None => this // Ignore "done" messages for orders not in the book
    }

  def change(id: String, newSize: Double): OrderBook = {
    orders(id) match {
      case o@Order(_, Sell, _, Some(price)) => copy(
        orders = orders + (id -> o.copy(amount = newSize)),
        asks = asks + (price -> (asks(price) - o + o.copy(amount = newSize))))
      case o@Order(_, Buy, _, Some(price)) => copy(
        orders = orders + (id -> o.copy(amount = newSize)),
        bids = bids + (price -> (bids(price) - o + o.copy(amount = newSize))))
    }
  }

  private def addToIndex(idx: TreeMap[Double, Set[Order]], o: Order): TreeMap[Double, Set[Order]] =
    idx + (o.price.get -> (idx.getOrElse(o.price.get, Set.empty) + o))

  private def rmFromIndex(idx: TreeMap[Double, Set[Order]], o: Order):
  TreeMap[Double, Set[Order]] = {
    val os = idx(o.price) - o
    if (os.isEmpty) idx - o.price.get else idx + (o.price.get -> os)
  }
}

object OrderBook {
  case class OrderBookMD[E <: RawOrderEvent](source: String,
                                             topic: String,
                                             seq: Long = 0,
                                             rawEvent: Option[E] = None,
                                             data: OrderBook = OrderBook())
    extends GenMD[OrderBook] with Sequenced with HasProduct {

    override def dataType: String = "book"
    override def product: Pair = parseProductId(topic)
    override def time: Long = rawEvent.get.time

    def addSnapshot(seq: Long, snapshot: Seq[SnapshotOrder]): OrderBookMD[E] = copy(
      seq = seq,
      data = snapshot.foldLeft(data) {
        case (memo, SnapshotOrder(_, _, bid, id, price, size)) =>
          memo.open(id, price, size, if (bid) Buy else Sell)
      }
    )

    def getSnapshot: Seq[SnapshotOrder] = data.orders.values.map {
      case Order(id, side, amount, Some(price)) =>
        SnapshotOrder(product.toString, seq, side == Buy, id, price, amount)
    }.toSeq

    def addEvent(event: E): OrderBookMD[E] = copy(
      seq = event.seq,
      data = data.processOrderEvent(event.toOrderEvent),
      rawEvent = Some(event)
    )
  }

  final case class SnapshotOrder(product: String,
                                 seq: Long,
                                 bid: Boolean,
                                 id: String,
                                 price: Double,
                                 size: Double)
}
