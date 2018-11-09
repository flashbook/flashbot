//package sources
//
//import java.io.File
//
//import akka.actor.ActorSystem
//import akka.stream.ActorMaterializer
//import io.flashbook.flashbot.core.DataSource.{Address, DataTypeConfig}
//import io.flashbook.flashbot.core._
//import io.circe.Json
//import io.circe.parser._
//import io.circe.syntax._
//import shaded.org.apache.commons.io.FilenameUtils
//
//import scala.io.Source
//
//class ResourceMarketDataSource extends DataSource {
//
//  override def ingest(dataDir: String,
//                      topics: Map[String, Json],
//                      dataTypes: Map[String, DataTypeConfig])
//                     (implicit sys: ActorSystem, mat: ActorMaterializer): Unit = ???
//
//  override def stream(dataDir: String,
//                      topic: String,
//                      dataType: String,
//                      timeRange: TimeRange): Iterator[MarketData] = {
//
//    val jsonIt = FilenameUtils.getExtension(topic) match {
//      case "csv" =>
//        val DataType: Option[DataSource.BuiltInType] = DataSource.parseBuiltInDataType(dataType)
//
//        val it = Source
//          .fromInputStream(getClass.getResourceAsStream("/data/" + topic))
//          .getLines
//
//        val headers = it.next().split(",")
//
//        it.map(headers zip _.split(","))
//          .map(_.toMap)
//
////      case _ =>
////        Source.fromInputStream(getClass.getResourceAsStream("/" + topic)).getLines
//    }
//
//    dataType match {
//      case "candles" =>
//        jsonIt.map(CandleMD())
//    }
//  }
//}
