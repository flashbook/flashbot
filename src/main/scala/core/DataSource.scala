package core

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import io.circe.Json
import io.circe.generic.auto._

abstract class DataSource {
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

  def parseBuiltInDataType(ty: String): Option[BuiltInType] = ty.split("_").toList match {
    case "book" :: Nil => Some(FullBook)
    case "book" :: d :: Nil if d matches "[0-9]+" => Some(DepthBook(d.toInt))
    case "trades" :: Nil => Some(Trades)
    case _ => None
  }
}
