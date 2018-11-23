package io.flashbook.flashbot.sources

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import io.flashbook.flashbot.core.{DataSource, MarketData, TimeRange, Timestamped}
import io.circe.Json
import io.flashbook.flashbot.core.DataSource.DataTypeConfig

object TwitterSearchDataSource {
  type Tweet = String
}

class TwitterSearchDataSource(topics: Map[String, Json],
                              dataTypes: Map[String, DataTypeConfig])
    extends DataSource(topics, dataTypes) {

  override def ingestGroup(topics: Set[String], dataType: String)
                          (implicit sys: ActorSystem,
                           mat: ActorMaterializer): Map[String, Source[Timestamped, NotUsed]] = ???

  override def stream(dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: TimeRange): Iterator[MarketData] = ???

  override def ingest(topic: String, dataType: String)(implicit sys: ActorSystem, mat: ActorMaterializer) = ???
}
