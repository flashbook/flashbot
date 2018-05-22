package core

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import io.circe.Json

abstract class DataSource {
  def ingest(dataDir: String,
             topics: Map[String, Json],
             dataTypes: Map[String, DataSource.DataTypeConfig])
            (implicit sys: ActorSystem,
             mat: ActorMaterializer)

  def stream(sink: Sink[MarketData, NotUsed],
             dataDir: String,
             topic: String,
             dataType: String,
             timeRange: TimeRange): Unit
}

object DataSource {
  final case class DataTypeConfig(retention: String)

  final case class DataSourceConfig(`class`: String,
                                    topics: Map[String, Json],
                                    data_types: Map[String, DataTypeConfig])

  final case class Address(srcKey: String, topic: String, dataType: String) {
    override def toString: String = List(srcKey, topic, dataType).mkString("/")
  }

  def parseAddress(addr: String): Address = addr.split("/") match {
    case (srcKey: String) :: (topic: String) :: (dataType: String) :: Nil =>
      Address(srcKey, topic, dataType)
  }

  trait DataType
  sealed trait BuiltInType extends DataType
  case object FullBook extends BuiltInType
  case class DepthBook(depth: Int) extends BuiltInType
  case object Trades extends BuiltInType

  def parseBuiltInDataType(ty: String): Option[BuiltInType] = ty.split("_") match {
    case "book" :: Nil => Some(FullBook)
    case "book" :: (d: String) :: Nil if d matches "[0-9]+" => Some(DepthBook(d.toInt))
    case "trades" :: Nil => Some(Trades)
    case _ => None
  }
}
