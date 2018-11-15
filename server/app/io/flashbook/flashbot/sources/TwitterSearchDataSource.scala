package io.flashbook.flashbot.sources

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import io.flashbook.flashbot.core.{DataSource, MarketData, TimeRange}
import io.circe.Json

object TwitterSearchDataSource {
  type Tweet = String
}

class TwitterSearchDataSource extends DataSource {
  override def ingest(dataDir: String,
                      topics: Map[String, Json],
                      dataTypes: Map[String, DataSource.DataTypeConfig])
                     (implicit sys: ActorSystem,
                      mat: ActorMaterializer): Unit = ???

  override def stream(dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: TimeRange): Iterator[MarketData] = ???
}
