import scopt.OptionParser
import java.io.File

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.{ActorMaterializer, Materializer}
import core.DataSource
import data.GdaxMarketDataSource
import io.circe.DecodingFailure
import io.prometheus.client.exporter.HTTPServer

import scala.concurrent.ExecutionContext
import scala.io.Source

object Main {

  case class Opts(dataPath: String = "flashbot_data",
//                  logPath: String = "flashbot_logs",
                  port: Int = 9020,
                  metricsPort: Int = 0,
                  cmd: String = "",
                  config: File = new File("."),
                  name: String = "",
                  sources: Seq[String] = List.empty)

  private val optsParser = new OptionParser[Opts]("flashbot") {
    head("flashbot", "0.1")

    opt[String]('d', "dataPath").action((x, c) => c.copy(dataPath = x))
//    opt[String]('l', "logPath").action((x, c) => c.copy(logPath = x))

    opt[Int]('m', "metricsPort")
      .action((x, c) => c.copy(metricsPort = x))
      .text("Which port to expose the Prometheus metrics server on. Defaults to 0 (off)")

    opt[File]('c', "config")
      .action((x, c) => c.copy(config = x))
      .text("Required config JSON file")

    opt[String]('n', "name")
      .action((x, c) => c.copy(name = x))
      .text("The unique name of this service instance")
      .required()

    cmd("ingest").action((_, c) => c.copy(cmd = "ingest"))
      .text("Connect to a live market data source and record all received data.")
      .children(
        opt[Seq[String]]('s', "sources")
          .action((x, c) => c.copy(sources = x))
          .text("Comma separated list of data sources to ingest"))

    cmd("trade").action((_, c) => c.copy(cmd = "trade"))
      .text("Start a local trading server for running strategies in a (live or simulated) environment.")
      .children(
        opt[Int]('p', "port")
          .action((x, c) => c.copy(port = x))
          .text("Which port to expose the HTTP API on. Defaults to 9020.")
      )

    help("help").text("Prints this usage text")
      .required()

    checkConfig(c =>
      if (c.cmd.isEmpty) failure("enter a command")
      else success)
  }

  private var metricsServer: Option[HTTPServer] = None

//  val gauge = Gauge.build("market_data_event_buffer_length", "buffer length")
//    .labelNames("product")
//    .register()

  implicit val system: ActorSystem = ActorSystem("flashbot")
  implicit val mat: Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  def main(args: Array[String]): Unit = {
    import io.circe.generic.auto._
    import io.circe.syntax._

    val opts = optsParser.parse(args, Opts()).get
    val config = Source.fromFile(opts.config).getLines.mkString.asJson.as[ConfigFile].right.get

    if (opts.metricsPort != 0)
      metricsServer = Some(new HTTPServer(opts.metricsPort))

    opts.cmd match {
      case "ingest" =>
        val srcNames =
          if (opts.sources.isEmpty) config.data_sources.keySet
          else opts.sources

        srcNames.foreach(srcName => {
          val actor = system.actorOf(Props[IngestService], s"ingest:$srcName")
          actor ! (srcName, config.data_sources(srcName))
        })

      case "trade" =>
        val engine = system.actorOf(Props[core.TradingEngine], "trading-engine")
        Http().bindAndHandle(api.routes(Map.empty, engine), "localhost", 9020)
    }

  }

  class IngestService extends Actor with ActorLogging {
    override def receive: Receive = {
      case (name: String, config: DataSource.DataSourceConfig) =>
        config.topics.foreach({ case (topicName, _) =>
            config.data_types.foreach({ case (typeName, _) =>
                log.info("Ingesting {}/{}/{}", name, topicName, typeName)
            })
        })
        Class.forName(config.`class`).newInstance.asInstanceOf[DataSource]
          .ingest(config.topics, config.data_types)
    }
  }
}
