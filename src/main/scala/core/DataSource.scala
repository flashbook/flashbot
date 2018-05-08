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

  def stream[T <: MarketData](topic: String,
                              dataType: String,
                              period: TimeRange): Source[T, NotUsed]
}

object DataSource {
  final case class DataTypeConfig(retention: String)

  final case class DataSourceConfig(`class`: String,
                                    topics: Map[String, Json],
                                    data_types: Map[String, DataTypeConfig])
}
