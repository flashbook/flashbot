package io.flashbook.flashbot.engine

import java.io.File
import java.util.concurrent.Executors

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.pattern.pipe
import io.circe.Json
import io.flashbook.flashbot.core._
import io.flashbook.flashbot.core.DataSource._
import io.flashbook.flashbot.core.MarketData.BaseMarketData
import io.flashbook.flashbot.core.Slice.SliceId
import io.flashbook.flashbot.util.time._
import io.flashbook.flashbot.util.stream._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * An actor that runs a single instance of a data source. Supervised by DataServer.
  * It answers requests for data by sending out a StreamWrap that's ready to send data.
  * It is also indexes the data that it ingests, so that it can answer queries about what
  * data exists for what time frames.
  */
object DataSourceActor {
  case class Ingest(matchers: Set[DataPath])
  case object Index

  case class StreamMarketData[T](selection: StreamSelection,
                                 matchId: SliceId = SliceId.wildcard)
    extends StreamRequest[T]
}

class DataSourceActor(marketDataPath: File, srcKey: String, config: DataSourceConfig)
    extends Actor with ActorLogging {
  import DataSourceActor._

  implicit val conf: DataSourceConfig = config

  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(100))
  implicit val system = context.system
  implicit val mat = ActorMaterializer()

  val snapshotInterval = 4 hours


  val cls = getClass.getClassLoader.loadClass(config.`class`)
  val constructor = cls
    .getConstructor(classOf[Map[String, Json]], classOf[Map[String, DataTypeConfig]])

  val dataSource = constructor
    .newInstance(config.topics, config.data_types)
    .asInstanceOf[DataSource]

  val types: Map[String, DeltaFmtJson[_]] = dataSource.types

  val paths: Set[DataPath] = config.topics.keySet.flatMap(topic =>
    config.data_types.keySet.map(dt => DataPath(srcKey, topic, dt)))

  var ingestQueue: Seq[(String, Set[String])] = config.data_types.toSeq.map {
    case (dataType, _) => (dataType, config.topics.keySet)
  }

  override def receive = {
    case Ingest(matchers) =>

      // Filter out any ingest items that don't match.
      ingestQueue = ingestQueue.map {
        case (dt, topics) => (dt, topics.filter(topic =>
          matchers.exists(_.matches(DataPath(srcKey, topic, dt)))))
      }.filter(_._2.nonEmpty)

      // Take the first topic set and schedule
      val (dataType, topicSet) = ingestQueue.head
      runScheduledIngest(types(dataType))

      // We got a data type and some topics to ingest. Get the data streams from the data source
      // instance, drop all data that doesn't match the ingest config, and return the streams.
      def getStreams[T](topics: Either[String, Set[String]], delay: Duration,
                        matchers: Set[DataPath])(implicit fmt: DeltaFmtJson[T]) = for {
        _ <- Future { Thread.sleep(delay.toMillis) }

        streams <- topics match {
          case Left(topic) => dataSource.ingest(topic, fmt).map(src => Map(topic -> src))
          case Right(ts) => dataSource.ingestGroup(ts, fmt)
        }
      } yield streams.filterKeys(topic =>
          matchers.exists(_.matches(DataPath(srcKey, topic, dataType))))

      def runScheduledIngest[T](implicit fmt: DeltaFmtJson[T]) = {
        var scheduledTopics = Set.empty[String]
        val fut = dataSource.scheduleIngest(topicSet, dataType) match {
          case IngestOne(topic, delay) =>
            scheduledTopics = Set(topic)
            getStreams(Left(topic), delay, matchers)
          case IngestGroup(topics, delay) =>
            scheduledTopics = topics
            getStreams(Right(topics), delay, matchers)
        }
        val remainingTopics = topicSet -- scheduledTopics
        if (remainingTopics.nonEmpty) {
          ingestQueue = (dataType, remainingTopics) +: ingestQueue.tail
        } else {
          ingestQueue = ingestQueue.tail
        }

        fut onComplete {
          case Success(sources) =>

            // Start the next ingest cycle
            if (ingestQueue.nonEmpty)
              self ! Ingest(matchers)

            // Run the source for each topic and save it to disk.
            sources.foreach {
              case (topic, source) =>
                val deltaLog = iDeltaLog[T](topic)
                source.runForeach {
                  case (micros, data) =>
                    deltaLog.save(micros, data)
                }
            }

          case Failure(err) =>
            // TODO: Handle failure
            log.error(err, s"Ingest failure in DataSource $srcKey.")
        }

      }

    /**
      * Respond with a TopicIndex to whoever is asking.
      */
    case Index =>
      // Create an IndexedDeltaLog for every possible combination of topic + dataType. This
      // ensures our index is complete. Many of the ephemeral IndexedDeltaLogs will be empty,
      // but that's ok.
      sender ! TopicIndex.build(paths.groupBy(_.topic).map {
        case (topic, paths2) =>
          topic -> DataTypeIndex.build(paths2.groupBy(_.dataType).map {
            case (dt, paths3) =>
              dt -> SliceIndex.build(paths3.toSeq.flatMap(a =>
                iDeltaLog(a.topic)(types(a.dataType)).index.slices))
          })
      })

    /**
      * Returns a SourceRspList of MarketData. One stream for each bundle matched.
      */
    case StreamMarketData(StreamSelection(path, from, to, polling), sliceMatch) =>
      def buildRsp[T](implicit fmt: DeltaFmtJson[T]): Future[SourceRspList[MarketData[T]]] =
        StreamResponse.buildList(bundleStreams(path.topic, from, to, polling, sliceMatch), sender)
      buildRsp(types(path.dataType)) pipeTo sender
  }

  def bundleStreams[T](topic: String, fromMicros: Long, toMicros: Long,
                       polling: Boolean, sliceMatch: SliceId)
                      (implicit fmt: DeltaFmtJson[T]): List[Source[MarketData[T], NotUsed]] =
    iDeltaLog(topic)
      .scan(sliceMatch.matches, fromMicros, toMicros, polling)
      .toList.zipWithIndex
      .map { case (it, bundle) =>
        it.map { case (data, micros) =>
          BaseMarketData[T](data, DataPath(srcKey, topic, fmt.fmtName), micros, bundle)
        }.toSource
      }


  def iDeltaLog[T](topic: String)(implicit fmt: DeltaFmtJson[T]): IndexedDeltaLog[T] =
    new IndexedDeltaLog[T](
      new File(marketDataPath, s"$srcKey/$topic/${fmt.fmtName}"),
      config.data_types(fmt.fmtName).retention, 4 hours)
}
