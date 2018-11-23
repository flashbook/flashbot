package io.flashbook.flashbot.engine

import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.Actor
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import io.circe.Json
import io.flashbook.flashbot.core.{DataSource, DeltaFmt, Timestamped}
import io.flashbook.flashbot.core.DataSource._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * An actor that runs a single instance of a data source. Supervised by DataServer.
  * It answers requests for data by sending out a remote stream ref. It is also indexes
  * the data that it ingests, so that it can answer queries about what data exists for
  * what time frames.
  */
class DataSourceActor(config: DataSourceConfig) extends Actor {

  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
  implicit val system = context.system
  implicit val mat = ActorMaterializer()

  val snapshotInterval = 4 hours

  val cls = getClass.getClassLoader.loadClass(config.`class`)
  val constructor = cls
    .getConstructor(classOf[Map[String, Json]], classOf[Map[String, DataTypeConfig]])

  val dataSource = constructor
    .newInstance(config.topics, config.data_types)
    .asInstanceOf[DataSource]

  var ingestQueue: Seq[(String, Set[String])] = config.data_types.toSeq.map {
    case (dataType, _) => (dataType, config.topics.keySet)
  }

  self ! Ingest

  case object Ingest
  case class Index()

  override def receive = {
    case Ingest =>
      val (dataType, topicSet) = ingestQueue.head
      var scheduledTopics = Set.empty[String]
      val fut = dataSource.scheduleIngest(topicSet, dataType) match {
        case IngestOne(topic, delay) =>
          scheduledTopics = Set(topic)
          ingestTopics(Left(topic), dataType, delay)
        case IngestGroup(topics, delay) =>
          scheduledTopics = topics
          ingestTopics(Right(topics), dataType, delay)
      }
      val remainingTopics = topicSet -- scheduledTopics
      if (remainingTopics.nonEmpty) {
        ingestQueue = (dataType, remainingTopics) +: ingestQueue.tail
      } else {
        ingestQueue = ingestQueue.tail
      }

      fut onComplete {
        case Success(_) =>
          if (ingestQueue.nonEmpty)
            self ! Ingest
        case Failure(err) =>
          // TODO: Handle failure
      }
  }

  def ingestTopics(topics: Either[String, Set[String]], dataType: String, delay: Duration): Future[Unit] = ???

//  def ingestTopics(topics: Either[String, Set[String]], dataType: String, delay: Duration) = Future {
//    Thread.sleep(delay.toMillis)
//    topics match {
//      case Left(t) =>
//        handleIngestStream(dataType, dataSource.ingest(t, dataType))
//      case Right(ts) =>
//        dataSource.ingestGroup(ts, dataType).foreach {
//          case (dt, src) => handleIngestStream(dt, src)
//        }
//    }
//  }

  /**
    * We got a stream of T from the data source, and we need to save it to an [[IndexedDeltaLog]].
    */
  def handleIngestStream[T](dataType: String, fmt: DeltaFmt[T], src: Source[T, NotUsed]) = {

//    parseBuiltInDataType(dataType) match {
//      case Some(dt: BuiltInType[T]) =>
//        src.asInstanceOf[Source[T, ]].to(Sink.foreach(x => x)
//      case _ =>
//        throw new RuntimeException("Custom data sources not yet supported")
//    }
  }
}
