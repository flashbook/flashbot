package io.flashbook.flashbot.engine

import java.io.File
import java.util

import io.circe.{Decoder, Encoder, Printer}
import io.flashbook.flashbot.core.Timestamped
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
    override def onReleased(cycle: Int, file: File): Unit = {
      // TODO: Do retention things here
//      println("file released", cycle, file)
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

    import io.circe.parser._
    import io.circe.syntax._

    private val RetentionPeriod = 1000 * 60 * 60 * 24 * 30

    private def microsToMillis(micros: Long) = math.floor(micros.toDouble / 1000).toLong
    var lastMessage: Option[T] = None
    var inFlightMessage: Option[T] = None

    object TimestampProvider extends TimeProvider {
      override def currentTimeMillis: Long =
        inFlightMessage.orElse(lastMessage).map(x => microsToMillis(x.micros)).getOrElse(0)
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
      lastMessage = inFlightMessage
      inFlightMessage = None
    }

    class TimeLogIterator(tailer: ExcerptTailer,
                          shouldContinue: T => Boolean,
                          reader: NextMsgReader)
                         (onComplete: () => Unit = () => {})
                         (implicit val de: Decoder[T]) extends Iterator[T] {

      private var _next: Option[T] = None

      override def hasNext: Boolean = {
        if (_next.isDefined) {
          return true
        }

        val tmpNext = reader
          .read(tailer, pauser)
          .map(decode[T])
          .map(_.right.get)
        if (tmpNext.isDefined && shouldContinue(tmpNext.get)) {
          _next = tmpNext
        } else {
          onComplete()
        }
        _next.isDefined
      }

      override def next: T = {
        val ret = _next.get
        _next = None
        ret
      }
    }

    def scanBackwards(shouldContinue: T => Boolean)
                     (onComplete: () => Unit = () => {})
                     (implicit de: Decoder[T]): Iterator[T] =
      new TimeLogIterator(
        queue.createTailer.direction(TailerDirection.BACKWARD).toEnd,
        shouldContinue,
        NoPollingReader
      )(onComplete)

    def size: Long = queue.entryCount()

    def scan[U](from: U,
                comparing: T => U,
                shouldContinue: T => Boolean,
                duration: ScanDuration = ScanDuration.Continuous)
               (onComplete: () => Unit = () => {})
               (implicit de: Decoder[T],
                ordering: Ordering[U]): Iterator[T] =
      new TimeLogIterator(
        _search(queue.createTailer(), comparing, from),
        shouldContinue,
        if (duration == ScanDuration.Finite) NoPollingReader else PollingReader
      )(onComplete)

//    private def _scan(handler: T => Boolean, tailer: ExcerptTailer, reader: NextMsgReader)
//                     (implicit de: Decoder[T]): Unit = {
//      var msg: Option[String] = reader.read(tailer, pauser)
//      while (msg.isDefined) {
//        msg = if (handler(decode(msg.get).right.get)) reader.read(tailer, pauser) else None
//      }
//    }

    def close(): Unit = {
//      println("CLOSING")
      queue.close()
    }

    // Binary search code taken from net.openhft.chronicle.queue.impl.single.BinarySearch
    def _search[U](tailer: ExcerptTailer,
                   comparing: T => U,
                   key: U)
                  (implicit de: Decoder[T],
                   ordering: Ordering[U]): ExcerptTailer = {
      val start = tailer.toStart.index
      val end = tailer.toEnd.index
      val rollCycle: RollCycle = queue.rollCycle
      val startCycle = rollCycle.toCycle(start)
      val endCycle = rollCycle.toCycle(end)

      def findWithinCycle(cycle: Int): Long = {
        var lowSeqNum: Long = 0
        var highSeqNum = queue.exceptsPerCycle(cycle) - 1
        if (highSeqNum == 0)
          return rollCycle.toIndex(cycle, 0)

        if (highSeqNum < lowSeqNum)
          return -1

        var midIndex: Long = 0

        while (lowSeqNum <= highSeqNum) {
          val midSeqNum = (lowSeqNum + highSeqNum) >>> 1L
          midIndex = rollCycle.toIndex(cycle, midSeqNum)
          tailer.moveToIndex(midIndex)

          val dc = Option(tailer.readText())
          if (dc.isEmpty)
            return -1

          val compare = ordering.compare(comparing(decode(dc.get).right.get), key)
          if (compare < 0)
            lowSeqNum = midSeqNum + 1
          else if (compare > 0)
            highSeqNum = midSeqNum - 1
          else
            return midIndex
        }

        -midIndex
      }

      def findCycleLinearSearch(cycles: util.NavigableSet[java.lang.Long]): Long = {
        val iterator = cycles.iterator
        if (!iterator.hasNext)
          return -1

        val rollCycle = queue.rollCycle
        var prevIndex = iterator.next

        while (iterator.hasNext) {
          val current = iterator.next
          val b = tailer.moveToIndex(rollCycle.toIndex(current.toInt, 0))
          if (!b)
            return prevIndex

          val compare = ordering.compare(comparing(decode(tailer.readText).right.get), key)
          if (compare == 0) {
            return compare
          } else if (compare > 0) {
            return prevIndex
          }
          prevIndex = current
        }
        prevIndex
      }

      def findIt: Long = {
        if (startCycle == endCycle)
          return findWithinCycle(startCycle)

        val cycles = queue.listCyclesBetween(startCycle, endCycle)
        val cycle = findCycleLinearSearch(cycles)
        if (cycle == -1)
          return -1

        findWithinCycle(cycle.toInt)
      }

      val found: Long = findIt
//      println("found", found)
//      println(tailer.toStart.index, tailer.toEnd.index)

      tailer.moveToIndex(found match {
        case -1 => 0
        case x => math.abs(x)
      })
      tailer
    }
  }
}
