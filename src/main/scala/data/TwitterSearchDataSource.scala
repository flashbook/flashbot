package data

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import core.{DataSource, MarketData}
import io.circe.Json

object TwitterSearchDataSource {
  type Tweet = String
}

class TwitterSearchDataSource extends DataSource("twitter") {
  override def ingest(topics: Map[String, Json], dataTypes: Map[String, DataSource.DataTypeConfig])(implicit sys: ActorSystem): Unit = ???

  override def index(topic: String, dataType: String): Seq[core.TimeRange] = ???

  override def stream[T <: MarketData](topic: String, dataType: String, period: core.TimeRange): Source[T, NotUsed] = ???
}
