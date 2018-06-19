package core

import core.MarketData.GenMD
import core.Order.{Buy, Side}
import core.Utils.parseProductId
import io.circe.{KeyDecoder, KeyEncoder}
import io.circe.generic.auto._

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

  def toTreeMap(map: Map[Double, Double]): TreeMap[Double, Double] =
    map.foldLeft(TreeMap.empty[Double, Double])(_ + _)

  def aggFillOrder(book: AggBook, side: Side, sizeOrFunds: Double): Seq[(Double, Double)] = {
    val bookSide = if (side == Buy) book.asks else book.bids
    val bookSideSeq = bookSide.asInstanceOf[TreeMap[Double, Double]].toSeq
    // If looking at asks, sort by increasing, otherwise, decreasing
    val bookSideIt = if (side == Buy) bookSideSeq.iterator else bookSideSeq.reverseIterator
    var remainingAmount = sizeOrFunds
    var fills = Seq.empty[(Double, Double)]
    while (remainingAmount > 0 && bookSideIt.hasNext) {
      val (price, quantity) = bookSideIt.next
      if (side == Buy) {
        val min = math.min(remainingAmount, price * quantity)
        fills = fills :+ (price, min / price)
        remainingAmount = remainingAmount - min
      } else {
        // TODO: Not multiplying price here ... something is wrong. I think fills needs
        // to have min * price
        val min = math.min(remainingAmount, quantity)
        fills = fills :+ (price, min)
        remainingAmount = remainingAmount - min
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

  private def updateMap(map: Map[Double, Double],
                        priceLevel: Double,
                        quantity: Double): Map[Double, Double] =
    quantity match {
      case 0 => map - priceLevel
      case _ => map + (priceLevel -> quantity)
    }
}
