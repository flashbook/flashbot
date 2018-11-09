package io.flashbook.flashbot.core

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import io.circe.Json

import scala.concurrent.duration.FiniteDuration

abstract class DataSource {

  def index(implicit sys: ActorSystem,
            mat: ActorMaterializer): Set[String] = {
    throw new NotImplementedError
  }

  def ingest(dataDir: String,
             topics: Map[String, Json],
             dataTypes: Map[String, DataSource.DataTypeConfig])
            (implicit sys: ActorSystem,
             mat: ActorMaterializer)

  def stream(dataDir: String,
             topic: String,
             dataType: String,
             timeRange: TimeRange): Iterator[MarketData]
}

object DataSource {
  final case class DataTypeConfig(retention: String)

  final case class DataSourceConfig(`class`: String,
                                    topics: Map[String, Json],
                                    data_types: Map[String, DataTypeConfig])

  final case class Address(srcKey: String, topic: String, dataType: String) {
    override def toString: String = List(srcKey, topic, dataType).mkString("/")
  }

  def parseAddress(addr: String): Address = addr.split("/").toList match {
    case srcKey :: topic :: dataType :: Nil => Address(srcKey, topic, dataType)
  }

  trait DataType
  sealed trait BuiltInType extends DataType
  case object FullBook extends BuiltInType
  case class DepthBook(depth: Int) extends BuiltInType
  case object Trades extends BuiltInType
  case object Tickers extends BuiltInType
  case class Candles(duration: FiniteDuration) extends BuiltInType

  def parseBuiltInDataType(ty: String): Option[BuiltInType] = ty.split("_").toList match {
    case "book" :: Nil => Some(FullBook)
    case "book" :: d :: Nil if d matches "[0-9]+" => Some(DepthBook(d.toInt))
    case "candles" :: d :: Nil => Some(Candles(Utils.parseDuration(d)))
    case "trades" :: Nil => Some(Trades)
    case "tickers" :: Nil => Some(Tickers)
    case _ => None
  }
}
