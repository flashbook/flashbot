package core

import core.MarketData.GenMD
import core.Utils.parseProductId
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
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
