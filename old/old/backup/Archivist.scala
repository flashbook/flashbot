package core

import akka.actor.Actor
import akka.event.Logging
import org.apache.spark.SparkConf
import org.apache.spark.sql.{SaveMode, SparkSession}

import scala.collection.immutable.Queue

// The archivist just saves the events it receives to disk in batches.
class Archivist extends Actor {

  private val conf = new SparkConf().setMaster("local").setAppName("trader-tron-archivist")
  private val spark = SparkSession.builder().config(conf).getOrCreate()

  val log = Logging(context.system, this)

  var orderEvents: Queue[APIOrderEvent] = Queue.empty
  var orderSnapshots: Queue[SnapshotOrder] = Queue.empty

  override def receive: Receive = {

    case x: APIOrderEvent =>
      orderEvents = orderEvents.enqueue(x)

      // If it's full, flush it do disk.
      if (orderEvents.lengthCompare(10000) > 0) {
        spark
          .createDataFrame(orderEvents)
          .write.partitionBy("product_id")
          .mode(SaveMode.Append)
          .parquet("spark-warehouse/events")

        orderEvents = Queue.empty
        log.info("archived order events")
      }

    case o: SnapshotOrder =>
      orderSnapshots = orderSnapshots.enqueue(o)

    case SaveSnapshots =>
      spark
        .createDataFrame(orderSnapshots)
        .write.partitionBy("product", "seqId")
        .mode(SaveMode.Append)
        .parquet("spark-warehouse/snapshots")

      orderSnapshots = Queue.empty
      log.info("archived order snapshots")
  }
}

case class SaveSnapshots()

