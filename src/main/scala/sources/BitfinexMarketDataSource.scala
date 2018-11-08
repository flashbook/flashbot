package sources

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import core.DataSource
import io.circe.Json

class BitfinexMarketDataSource extends DataSource {
  override def ingest(dataDir: String, topics: Map[String, Json], dataTypes: Map[String, DataSource.DataTypeConfig])(implicit sys: ActorSystem, mat: ActorMaterializer) = ???

  override def stream(dataDir: String, topic: String, dataType: String, timeRange: core.TimeRange) = ???
}
