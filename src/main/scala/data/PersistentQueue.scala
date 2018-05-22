package data

import java.io.File

import io.circe.{Decoder, Encoder, Printer}
import net.openhft.chronicle.bytes.Bytes
import net.openhft.chronicle.queue._
import net.openhft.chronicle.queue.impl.StoreFileListener
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder
import net.openhft.chronicle.threads.Pauser

object PersistentQueue {

  sealed trait QueueBound
  object QueueBound {
    case object Start extends QueueBound
    case object End extends QueueBound
  }

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

  def apply[T](path: File): PersistentQueue[T] = new PersistentQueue[T](path)

  class PersistentQueue[T](path: File,
                           resourceManager: ResourceManager = defaultResourceManager) {

    import io.circe.syntax._
    import io.circe.parser._
    import TailerDirection._

    private val RetentionPeriod = 1000 * 60 * 60 * 24 * 30

    private val queue = SingleChronicleQueueBuilder
      .binary(path)
      .rollCycle(RollCycles.DAILY)
      .storeFileListener(resourceManager)
      .build()

    private val pauser = Pauser.balanced()

    private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

    def enqueue(msg: T)(implicit en: Encoder[T]): Unit = {
      val appender: ExcerptAppender = queue.acquireAppender
      appender.writeBytes(Bytes.fromString(printer.pretty(msg.asJson)))
      pauser.unpause()
    }

    // A scan that accumulates a value as it iterates.
    def reduceWhile[A](handler: (A, T) => (A, Boolean),
                       zero: A,
                       from: QueueBound = QueueBound.Start,
                       direction: TailerDirection = FORWARD,
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
        direction,
        duration
      )
      result
    }

    // By default, scans the entire table once in order.
    def scan(handler: T => Boolean,
             from: QueueBound = QueueBound.Start,
             direction: TailerDirection = FORWARD,
             duration: ScanDuration = ScanDuration.Finite)
            (implicit de: Decoder[T]): Unit = {
      var tailer: ExcerptTailer = queue.createTailer().direction(direction)
      tailer = if (from == QueueBound.Start) tailer.toStart else tailer.toEnd
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
  }
}
