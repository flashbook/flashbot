package core

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import io.circe.Json

abstract class DataSource(val dataPath: String) {
  def ingest(topics: Map[String, Json],
             dataTypes: Map[String, DataSource.DataTypeConfig])
            (implicit sys: ActorSystem)

  def index(topic: String, dataType: String): Seq[TimeRange]

  def stream(topic: String,
             dataType: String,
             timeRange: TimeRange): Source[MarketData, NotUsed]
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
}
