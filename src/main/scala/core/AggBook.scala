package core

import core.MarketData.GenMD
import core.Order.{Buy, Sell, Side}
import core.Utils.parseProductId
import io.circe._
import io.circe.{KeyDecoder, KeyEncoder}
import io.circe.generic.semiauto._

import scala.collection.immutable.TreeMap

object AggBook {


  implicit val doubleKeyEncoder: KeyEncoder[Double] = new KeyEncoder[Double] {
    override def apply(key: Double): String = key.toString
  }

  implicit val doubleKeyDecoder: KeyDecoder[Double] = new KeyDecoder[Double] {
    override def apply(key: String): Option[Double] = Some(key.toDouble)
  }

  case class AggBook(depth: Int,
                     asks: Map[Double, Double] = TreeMap.empty,
                     bids: Map[Double, Double] = TreeMap.empty) {

    def updateLevel(side: QuoteSide, priceLevel: Double, quantity: Double): AggBook =
      side match {
        case Bid =>
          copy(bids = updateMap(bids, priceLevel, quantity))
        case Ask =>
          copy(asks = updateMap(asks, priceLevel, quantity))
      }

    def convertToTreeMaps: AggBook = copy(
      asks = toTreeMap(asks),
      bids = toTreeMap(bids)
    )
  }

  implicit val aggBookDecoder: Decoder[AggBook] = deriveDecoder[AggBook]
  implicit val aggBookEncoder: Encoder[AggBook] = deriveEncoder[AggBook]

  def toTreeMap(map: Map[Double, Double]): TreeMap[Double, Double] =
    map.foldLeft(TreeMap.empty[Double, Double])(_ + _)

  def aggFillOrder(book: AggBook, side: Side,
                   sizeOpt: Option[Double], fundsOpt: Option[Double])
    : Seq[(Double, Double)] = {

    var fills = Seq.empty[(Double, Double)]
    (side, sizeOpt, fundsOpt) match {
      case (Buy, None, Some(funds)) =>
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

      case (Buy, Some(size), None) =>
        val ladder = book.asks.asInstanceOf[TreeMap[Double, Double]].toSeq.iterator
        var remainingSize = size
        while (remainingSize > 0) {
          if (!ladder.hasNext) {
            throw new RuntimeException("Book not deep enough to fill order")
          }
          val (price, quantity) = ladder.next
          val min = math.min(remainingSize, quantity)
          fills :+= (price, min)
          remainingSize -= min
        }

      case (Sell, Some(size), None) =>
        val ladder = book.bids.asInstanceOf[TreeMap[Double, Double]].toSeq.reverseIterator
        var remainingSize = size
        while (remainingSize > 0) {
          if (!ladder.hasNext) {
            throw new RuntimeException("Book not deep enough to fill order")
          }
          val (price, quantity) = ladder.next
          val min = math.min(remainingSize, quantity)
          fills :+= (price, min)
          remainingSize -= min
        }
    }

    fills
  }

  case class AggBookMD(source: String,
                       topic: String,
                       micros: Long,
                       data: AggBook) extends GenMD[AggBook] with Priced {
    def dataType: String = s"book_${data.depth}"
    def product: Pair = parseProductId(topic)

    override def exchange: String = source

    override def price: Double =
      (data.asks.asInstanceOf[TreeMap[Double, Double]].head._1 +
        data.bids.asInstanceOf[TreeMap[Double, Double]].last._1) / 2
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
}
