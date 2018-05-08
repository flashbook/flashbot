package data

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import core.{DataSource, MarketData, TimeRange}
import io.circe.Json

class GdaxMarketDataSource(dataPath: String) extends DataSource(dataPath) {
  override def ingest(topics: Map[String, Json],
                      dataTypes: Map[String, DataSource.DataTypeConfig])
                     (implicit system: ActorSystem): Unit = {
  }

  override def index(topic: String, dataType: String): Seq[TimeRange] = ???

  override def stream[T <: MarketData](topic: String,
                                       dataType: String,
                                       period: TimeRange): Source[T, NotUsed] = ???
}
