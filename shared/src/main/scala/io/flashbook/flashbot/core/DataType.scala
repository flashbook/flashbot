package io.flashbook.flashbot.core

import io.flashbook.flashbot.util.time.parseDuration

import scala.concurrent.duration.FiniteDuration

sealed trait DataType[T] {
  def name: String
}

object DataType {

  case object OrderBookType extends DataType[OrderBook] {
    override def name = "book"
  }
  case class LadderType(depth: Int) extends DataType[Ladder] {
    override def name = "ladder"
  }
  case object TradesType extends DataType[Trade] {
    override def name = "trades"
  }
  case object TickersType extends DataType[Ticker] {
    override def name = "tickers"
  }
  case class CandlesType(duration: FiniteDuration) extends DataType[Candle] {
    override def name = "candles"
  }

  implicit class FmtOps[T](dataType: DataType[T]) {
    def fmt: DeltaFmt[T] = DeltaFmt.default[T](dataType.name)
  }

  def parse(ty: String): Option[DataType[_]] = ty.split("_").toList match {
    case "book" :: Nil => Some(OrderBookType)
    case "book" :: d :: Nil if d matches "[0-9]+" => Some(LadderType(d.toInt))
    case "candles" :: d :: Nil => Some(CandlesType(parseDuration(d)))
    case "trades" :: Nil => Some(TradesType)
    case "tickers" :: Nil => Some(TickersType)
    case _ => None
  }
}
