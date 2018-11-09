package io.flashbook.flashbot.sources

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import io.flashbook.flashbot.core.{DataSource, MarketData, TimeRange}
import io.circe.Json

class BitMEXMarketDataSource extends DataSource {
  override def ingest(dataDir: String,
                      topics: Map[String, Json],
                      dataTypes: Map[String, DataSource.DataTypeConfig])
                     (implicit sys: ActorSystem,
                      mat: ActorMaterializer): Unit = {
    println("Ingesting BitMEX")
  }

  override def stream(dataDir: String,
                      topic: String,
                      dataType: String,
                      timeRange: TimeRange): Iterator[MarketData] = ???

  //  override def index(dataDir: String, topic: String, dataType: String): Seq[core.TimeRange] = ???
}
