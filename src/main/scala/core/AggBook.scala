package core

import core.MarketData.GenMD
import core.Utils.parseProductId

import scala.collection.immutable.TreeMap

case class AggBook(depth: Int,
                   asks: Map[Double, Double] = TreeMap.empty,
                   bids: Map[Double, Double] = TreeMap.empty) {
  import AggBook.updateMap

  def updateLevel(side: QuoteSide, priceLevel: Double, quantity: Double): AggBook =
    side match {
      case Bid =>
        copy(bids = updateMap(bids, priceLevel, quantity))
      case Ask =>
        copy(asks = updateMap(asks, priceLevel, quantity))
    }
}

object AggBook {
  case class AggBookMD(source: String,
                       topic: String,
                       time: Long,
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
