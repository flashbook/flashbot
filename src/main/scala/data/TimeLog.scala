package data

import java.io.File
import java.util
import java.util.Comparator

import core.MarketData.Timestamped
import io.circe.{Decoder, Encoder, Printer}
import net.openhft.chronicle.bytes.Bytes
import net.openhft.chronicle.core.time.TimeProvider
import net.openhft.chronicle.queue._
import net.openhft.chronicle.queue.impl.StoreFileListener
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder
import net.openhft.chronicle.threads.Pauser

object TimeLog {

  sealed trait ScanDuration
  object ScanDuration {
    case object Finite extends ScanDuration
    case object Continuous extends ScanDuration
  }

  class ResourceManager extends StoreFileListener {
    override def onAcquired(cycle: Int, file: File): Unit = {
      super.onAcquired(cycle, file)
      println("file acquired", cycle, file)
    }

    override def onReleased(cycle: Int, file: File): Unit = {
      // TODO: Do retention things here
      println("file released", cycle, file)
    }
  }

  private val defaultResourceManager = new ResourceManager

  trait NextMsgReader {
    def read(tailer: ExcerptTailer, pauser: Pauser): Option[String]
  }

  object NoPollingReader extends NextMsgReader {
    override def read(tailer: ExcerptTailer, pauser: Pauser): Option[String] =
      Option(tailer.readText())
  }

  object PollingReader extends NextMsgReader {
    override def read(tailer: ExcerptTailer, pauser: Pauser): Option[String] = {
      var result: Option[String] = None
      while (!tailer.queue.isClosed && result.isEmpty) {
        result = Option(tailer.readText())
        if (result.isDefined) {
          pauser.reset()
        } else {
          pauser.pause()
        }
      }
      result
    }
  }

  def apply[T <: Timestamped](path: File): TimeLog[T] = new TimeLog[T](path)

  class TimeLog[T <: Timestamped](path: File) {

    import io.circe.syntax._
    import io.circe.parser._

    private val RetentionPeriod = 1000 * 60 * 60 * 24 * 30

    var inFlightMessage: Option[T] = None
    object TimestampProvider extends TimeProvider {
      override def currentTimeMillis: Long =
        math.floor(inFlightMessage.get.time.toDouble / 1000000).toLong
    }

    private val queue = SingleChronicleQueueBuilder
      .binary(path)
      .rollCycle(RollCycles.DAILY)
      .timeProvider(TimestampProvider)
      .storeFileListener(defaultResourceManager)
      .build()

    private val pauser = Pauser.balanced()

    private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

    def enqueue(msg: T)(implicit en: Encoder[T]): Unit = {
      inFlightMessage = Some(msg)
      val appender: ExcerptAppender = queue.acquireAppender
      appender.writeBytes(Bytes.fromString(printer.pretty(msg.asJson)))
      pauser.unpause()
      inFlightMessage = None
    }

    // A scan that accumulates a value as it iterates.
    def reduceWhile[A](handler: (A, T) => (A, Boolean),
                       zero: A,
                       from: Long = 0,
                       duration: ScanDuration = ScanDuration.Finite)
                      (implicit de: Decoder[T]): A = {

      var result: A = zero
      scan(
        handler = msg => {
          val (newResult, shouldContinue) = handler(result, msg)
          result = newResult
          shouldContinue
        },
        from,
        duration
      )
      result
    }

    // By default, scans the entire table once in order.
    def scan(handler: T => Boolean,
             from: Long = 0,
             duration: ScanDuration = ScanDuration.Finite)
            (implicit de: Decoder[T]): Unit = {
      var tailer: ExcerptTailer = queue.createTailer()
      moveToTime(tailer, from)
      _scan(
        msg => handler(msg),
        tailer,
        if (duration == ScanDuration.Finite)
          NoPollingReader else
          PollingReader
      )
    }

    private def _scan(handler: T => Boolean, tailer: ExcerptTailer, reader: NextMsgReader)
                     (implicit de: Decoder[T]): Unit = {
      var msg: Option[String] = reader.read(tailer, pauser)
      while (msg.isDefined) {
        msg = if (handler(decode(msg.get).right.get)) reader.read(tailer, pauser) else None
      }
    }

    def close(): Unit = {
      queue.close()
    }

    // Binary search logic taken from net.openhft.chronicle.queue.impl.single.BinarySearch
    def moveToTime(tailer: ExcerptTailer, time: Long): Long = {
      val start = tailer.toStart.index
      val end = tailer.toEnd.index
      val rollCycle = queue.rollCycle
      val startCycle = rollCycle.toCycle(start)
      val endCycle = rollCycle.toCycle(end)

      def findWithinCycle(cycle: Int): Long = ???

      def findCycleLinearSearch(cycles: util.NavigableSet[java.lang.Long]): Long = ???

      if (startCycle == endCycle)
        return findWithinCycle(startCycle)

      val cycles = queue.listCyclesBetween(startCycle, endCycle)
      val cycle = findCycleLinearSearch(cycles)
      if (cycle == -1)
        return -1

      findWithinCycle(cycle.toInt)
    }
  }
}
