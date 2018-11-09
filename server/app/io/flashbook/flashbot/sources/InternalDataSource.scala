package io.flashbook.flashbot.sources

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.instances.duration
import io.flashbook.flashbot.core._
import io.circe.Json

import scala.concurrent.duration.FiniteDuration

//class InternalDataSource extends DataSource {
//
//  override def ingest(dataDir: String,
//                      topics: Map[String, Json],
//                      dataTypes: Map[String, DataSource.DataTypeConfig])
//                     (implicit sys: ActorSystem, mat: ActorMaterializer): Unit = ???
//
//  override def stream(dataDir: String,
//                      topic: String,
//                      dataType: String,
//                      timeRange: TimeRange): Iterator[MarketData] = {
//    topic match {
//
//      case "tick" =>
//        val duration = Utils.parseDuration(dataType)
//
//        object TickIterator extends Iterator[Tick] {
//          var count = 0
//          override def hasNext: Boolean = timeRange.from + count * duration.toMicros <= timeRange.to
//          override def next: Tick = {
//            val tick = Tick(timeRange.from + count * duration.toMicros, "internal", topic, dataType)
//            count += 1
//            tick
//          }
//        }
//        TickIterator
//    }
//  }
//}
