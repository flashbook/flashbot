package sources

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import core.{DataSource, MarketData}
import io.circe.Json

class BinanceMarketDataSource extends DataSource {
  override def ingest(dataDir: String,
                      topics: Map[String, Json],
                      dataTypes: Map[String, DataSource.DataTypeConfig])
                     (implicit sys: ActorSystem,
                      mat: ActorMaterializer): Unit = ???

  override def stream(sink: Sink[MarketData, NotUsed],
                       dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: core.TimeRange): Unit = ???
}
