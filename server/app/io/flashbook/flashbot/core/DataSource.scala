package io.flashbook.flashbot.core

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import io.circe.Json
import io.circe.generic.auto._
import io.flashbook.flashbot.core.DataSource._
import io.flashbook.flashbot.util.time.parseDuration

import scala.concurrent.Future
import scala.concurrent.duration._

abstract class DataSource(topics: Map[String, Json],
                          dataTypes: Map[String, DataTypeConfig]) {

  def discoverTopics(implicit sys: ActorSystem,
                     mat: ActorMaterializer): Future[Set[String]] =
    Future.successful(Set.empty)

  def typeProvider(dataType: String): DeltaFmt[_]

  def scheduleIngest(topics: Set[String], dataType: String): IngestSchedule =
    IngestOne(topics.head, 0 seconds)

  def ingestGroup(topics: Set[String], dataType: String)
                 (implicit sys: ActorSystem,
                  mat: ActorMaterializer): Map[String, Source[Timestamped, NotUsed]]

  def ingest(topic: String, dataType: String)
            (implicit sys: ActorSystem,
             mat: ActorMaterializer): Source[Timestamped, NotUsed]

  def stream(dataDir: String,
             topic: String,
             dataType: String,
             timeRange: TimeRange): Iterator[MarketData]
}

object DataSource {

  sealed trait IngestSchedule {
    def delay: Duration
  }
  final case class IngestGroup(topics: Set[String], delay: Duration) extends IngestSchedule
  final case class IngestOne(topic: String, delay: Duration) extends IngestSchedule

  final case class DataTypeConfig(retention: String)

  final case class DataSourceConfig(`class`: String,
                                    topics: Map[String, Json],
                                    data_types: Map[String, DataTypeConfig])

  final case class Address(srcKey: String, topic: String, dataType: String) {
    override def toString: String = List(srcKey, topic, dataType).mkString("/")
  }

  def parseAddress(addr: String): Address = addr.split("/").toList match {
    case srcKey :: topic :: dataType :: Nil => Address(srcKey, topic, dataType)
  }

  sealed trait BuiltInType[T] {
    def fmt: DeltaFmt[T]
  }
  case object FullBook extends BuiltInType[FullBook.type] {
    override def fmt: DeltaFmt[FullBook.type] = ???
  }
  case class DepthBook(depth: Int) extends BuiltInType[DepthBook] {
    override def fmt: DeltaFmt[DepthBook] = ???
  }
  case object Trades extends BuiltInType[Trades.type] {
    override def fmt = ???
  }
  case object Tickers extends BuiltInType[Tickers.type] {
    override def fmt = ???
  }
  case class Candles(duration: FiniteDuration) extends BuiltInType[Candles] {
    override def fmt = ???
  }

  def parseBuiltInDataType(ty: String): Option[BuiltInType[_]] = ty.split("_").toList match {
    case "book" :: Nil => Some(FullBook)
    case "book" :: d :: Nil if d matches "[0-9]+" => Some(DepthBook(d.toInt))
    case "candles" :: d :: Nil => Some(Candles(parseDuration(d)))
    case "trades" :: Nil => Some(Trades)
    case "tickers" :: Nil => Some(Tickers)
    case _ => None
  }
}

