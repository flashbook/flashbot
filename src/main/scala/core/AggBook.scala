package core

import core.MarketData.GenMD

import scala.collection.immutable.TreeMap

case class AggBook(depth: Int,
                   asks: TreeMap[Double, Double] = TreeMap.empty,
                   bids: TreeMap[Double, Double] = TreeMap.empty) {
}

object AggBook {
  case class AggBookMD(source: String,
                       topic: String,
                       time: Long,
                       data: AggBook) extends GenMD[AggBook] {
    def dataType: String = s"book_${data.depth}"
  }
}
