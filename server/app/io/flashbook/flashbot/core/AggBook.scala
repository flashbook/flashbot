package io.flashbook.flashbot.core

import io.flashbook.flashbot.core.MarketData.GenMD
import io.flashbook.flashbot.core.Order.{Buy, Sell, Side}
import io.flashbook.flashbot.core.Utils.parseProductId
import io.circe._
import io.circe.{KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto._

import scala.collection.immutable.{SortedMap, TreeMap}

object AggBook {

  implicit val doubleKeyEncoder: KeyEncoder[Double] = new KeyEncoder[Double] {
    override def apply(key: Double): String = key.toString
  }

  implicit val doubleKeyDecoder: KeyDecoder[Double] = new KeyDecoder[Double] {
    override def apply(key: String): Option[Double] = Some(key.toDouble)
  }

  case class AggBook(depth: Int,
                     asks: Map[Double, Double] = TreeMap.empty,
                     bids: Map[Double, Double] = TreeMap.empty(Ordering.by(-_))) {

    def updateLevel(side: QuoteSide, priceLevel: Double, quantity: Double): AggBook =
      side match {
        case Bid =>
          copy(bids = updateMap(bids, priceLevel, quantity))
        case Ask =>
          copy(asks = updateMap(asks, priceLevel, quantity))
      }

    def convertToTreeMaps: AggBook = copy(
      asks = toTreeMap(asks, reverse = false),
      bids = toTreeMap(bids, reverse = true)
    )

    def spread: Option[Double] = {
      if (asks.asInstanceOf[SortedMap[Double, Double]].firstKey >
          asks.asInstanceOf[SortedMap[Double, Double]].lastKey) {
        throw new RuntimeException("Asks out of order")
      }
      if (bids.asInstanceOf[SortedMap[Double, Double]].firstKey <
          bids.asInstanceOf[SortedMap[Double, Double]].lastKey) {
        throw new RuntimeException("Bids out of order")
      }

      if (asks.isEmpty || bids.isEmpty) None
      else Some(asks.asInstanceOf[SortedMap[Double, Double]].firstKey -
        bids.asInstanceOf[SortedMap[Double, Double]].firstKey)
    }

    def midMarketPrice: Option[Double] = {
      if (asks.isEmpty || bids.isEmpty) None
      else Some((asks.asInstanceOf[SortedMap[Double, Double]].firstKey +
        bids.asInstanceOf[SortedMap[Double, Double]].firstKey) / 2)
    }

    def quantityAtPrice(price: Double): Option[Double] =
      asks.get(price).orElse(bids.get(price))
  }

  implicit val aggBookDecoder: Decoder[AggBook] = deriveDecoder[AggBook]
  implicit val aggBookEncoder: Encoder[AggBook] = deriveEncoder[AggBook]

  def toTreeMap(map: Map[Double, Double], reverse: Boolean): TreeMap[Double, Double] =
    map.foldLeft(TreeMap.empty[Double, Double](Ordering.by(price =>
      if (reverse) -price else price
    )))(_ + _)

  /**
    * Match an incoming order against an aggregated order book. Emit fills.
    * TODO: Add Time-In-Force controls such as Fill Or Kill.
    */
  def aggFillOrder(book: AggBook,
                   side: Side,
                   sizeOpt: Option[Double],
                   fundsOpt: Option[Double],
                   limit: Option[Double] = None)
    : Seq[(Double, Double)] = {

    var fills = Seq.empty[(Double, Double)]
    (side, sizeOpt, fundsOpt) match {
      /**
        * Special case for when a market order is placed in terms of notional funds.
        * Does not support limits, crash if one is provided.
        */
      case (Buy, None, Some(funds)) =>
        if (limit.isDefined) {
          throw new RuntimeException("A limit order cannot be placed in terms of notional funds.")
        }

        val ladder = book.asks.asInstanceOf[TreeMap[Double, Double]].toSeq.iterator
        var remainingFunds = funds
        while (remainingFunds > 0) {
          if (!ladder.hasNext) {
            throw new RuntimeException("Book not deep enough to fill order")
          }
          val (price, quantity) = ladder.next
          val min = math.min(remainingFunds, price * quantity)
          fills :+= (price, min / price)
          remainingFunds -= min
        }

      /**
        * The general case, for both market and limit orders that are placed in terms
        * of base quantity.
        */
      case (_, Some(size), None) =>
        val ladder = side match {
          case Buy =>
            book.asks.asInstanceOf[TreeMap[Double, Double]].toSeq.iterator
          case Sell =>
            book.bids.asInstanceOf[TreeMap[Double, Double]].toSeq.iterator
        }
        var remainingSize = size
        var limitExceeded = false
        while (remainingSize > 0 && !limitExceeded) {
          if (!ladder.hasNext) {
            throw new RuntimeException("Book not deep enough to fill order")
          }
          val (price, quantity) = ladder.next
          if (limit.isDefined) {
            limitExceeded = side match {
              case Buy => price > limit.get
              case Sell => price < limit.get
            }
          }
          if (!limitExceeded) {
            val min = math.min(remainingSize, quantity)
            fills :+= (price, min)
            remainingSize -= min
          }
        }
    }

    fills
  }

  case class AggBookMD(source: String,
                       topic: String,
                       micros: Long,
                       data: AggBook) extends GenMD[AggBook] {
    def dataType: String = s"book_${data.depth}"
    def product: Pair = parseProductId(topic)
  }

  implicit val aggBookMDDecoder: Decoder[AggBookMD] = deriveDecoder[AggBookMD]
  implicit val aggBookMDEncoder: Encoder[AggBookMD] = deriveEncoder[AggBookMD]

  private def updateMap(map: Map[Double, Double],
                        priceLevel: Double,
                        quantity: Double): Map[Double, Double] =
    quantity match {
      case 0 => map - priceLevel
      case _ => map + (priceLevel -> quantity)
    }

  def fromOrderBook(depth: Int)(book: OrderBook): AggBook = {
    AggBook(depth,
      asks = book.asks.take(depth).mapValues(_.map(_.amount).sum),
      bids = book.bids.take(depth).mapValues(_.map(_.amount).sum))
  }
}
