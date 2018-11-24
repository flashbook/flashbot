import java.io.File

import org.scalatest._

class IndexedDeltaLogSpec extends FlatSpec with Matchers {

  var testFolder: File = _

  "IndexedDeltaLogSpec" should "work" in {
    val file = new File(testFolder.getAbsolutePath + "/trades")
    val nowMillis = System.currentTimeMillis
//    val trades = genTrades(1, nowMillis)
//    val tl = TimeLog[Trade](testFolder)

    true shouldBe true
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
