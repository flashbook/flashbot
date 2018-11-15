import java.io.File

import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.Printer._
import io.flashbook.flashbot.core.Order.{Buy, Sell}
import io.flashbook.flashbot.core.Trade
import io.flashbook.flashbot.engine.TimeLog
import io.flashbook.flashbot.engine.TimeLog.ScanDuration
import net.openhft.chronicle.queue.RollCycles
import org.scalatest._

import scala.concurrent.duration._

class TimeLogSpec extends FlatSpec with Matchers {

  var testFolder: File = _

  // One trade every minute
  def genTrades(n: Int, nowMillis: Long): Seq[Trade] = Seq.range(0, n).map(i =>
    Trade(i.toString, (nowMillis + (i minutes).toMillis) * 1000, (1000 + i).toDouble, (5 + i).toDouble,
      if (i % 2 == 0) Buy else Sell))

  "TimeLog" should "write and read a single item to a new queue" in {
    val file = new File(testFolder.getAbsolutePath + "/trades")
    val nowMillis = System.currentTimeMillis
    val trades = genTrades(1, nowMillis)
    val tl = TimeLog[Trade](testFolder)
    trades.foreach(trade => tl.enqueue(trade))
    var read = Seq.empty[Trade]
    var doneFired = false

    val it: Iterator[Trade] =
      for (trade <- tl.scan[Long](0, _.micros, _ => true, ScanDuration.Finite)(() => {
        doneFired = true
      })) yield {
        read :+= trade
        trade
      }

    val yielded = it.toSeq

    read.size shouldBe 1
    read.head shouldEqual trades.head
    yielded.size shouldBe 1
    yielded.head shouldEqual trades.head
    doneFired shouldBe true
  }

  "TimeLog" should "clean up files for a 1 hour retention period" in {
    val file = new File(testFolder.getAbsolutePath + "/trades")
    val nowMillis = System.currentTimeMillis
    val tl = TimeLog[Trade](testFolder, 1 hour, RollCycles.HOURLY)
    val (first30, after30) = genTrades(1000, nowMillis).splitAt(30)
    val (next60, after90) = after30.splitAt(60)
    val (another60, after150) = after90.splitAt(60)

    def firstTrade: Trade =
      tl.scan[Long](0, _.micros, _ => true, ScanDuration.Finite)().toSeq.head

    // Enqueue first 30, there should be no deletions yet.
    first30.foreach(tl.enqueue(_))
    firstTrade shouldEqual first30.head

    // Enqueue next 60, still no deletions.
    next60.foreach(tl.enqueue(_))
    firstTrade shouldEqual first30.head

    // Enqueue the final 60, now the first file should have deleted.
    another60.foreach(tl.enqueue(_))
    (firstTrade.micros > first30.last.micros) shouldBe true
  }

  private def deleteFile(file: File) {
    if (!file.exists) return
    if (file.isFile) {
      file.delete()
    } else {
      file.listFiles().foreach(deleteFile)
      file.delete()
    }
  }

  override def withFixture(test: NoArgTest) = {
    val tempFolder = System.getProperty("java.io.tmpdir")
    var folder: File = null
    do {
      folder = new File(tempFolder, "scalatest-" + System.nanoTime)
    } while (! folder.mkdir())
    testFolder = folder
    try {
      super.withFixture(test)
    } finally {
      deleteFile(testFolder)
    }
  }

}
