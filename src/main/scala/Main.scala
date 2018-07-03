import scopt.OptionParser
import java.io.File
import java.net.URI

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.Materializer
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import core.{CurrencyConfig, TradingEngine, Utils}
import core.DataSource.DataSourceConfig
import core.Exchange.ExchangeConfig
import data.IngestService
import io.circe.Json
import io.circe.parser._
import io.prometheus.client.exporter.HTTPServer

import scala.concurrent.ExecutionContext
import scala.io.Source

object Main {

  case class ConfigFile(api_key: String = "",
                        strategies: Map[String, String] = Map.empty,
                        exchanges: Map[String, ExchangeConfig] = Map.empty,
                        data_sources: Map[String, DataSourceConfig] = Map.empty,
                        currencies: Map[String, CurrencyConfig] = Map.empty)


  val DEFAULT_CONFIG_FILE = new File(".")

  case class Opts(dataPath: String = "flashbot_data",
//                  logPath: String = "flashbot_logs",
                  port: Int = 9020,
                  metricsPort: Int = 0,
                  cmd: String = "",
                  config: File = DEFAULT_CONFIG_FILE,
                  name: String = "",
                  sources: Seq[String] = List.empty,
                  topics: Seq[String] = List.empty,
                  types: Seq[String] = List.empty)

  private val optsParser = new OptionParser[Opts]("flashbot") {
    head("flashbot", "0.1")

    opt[String]('d', "dataPath").action((x, c) => c.copy(dataPath = x))
//    opt[String]('l', "logPath").action((x, c) => c.copy(logPath = x))

    opt[Int]('m', "metricsPort")
      .action((x, c) => c.copy(metricsPort = x))
      .text("Which port to expose the Prometheus metrics server on. Defaults to 0 (off)")

    opt[File]('c', "config")
      .action((x, c) => c.copy(config = x))
      .text("Config file")

    opt[String]('n', "name")
      .action((x, c) => c.copy(name = x))
      .text("The unique name of this service instance")
      .required()

    cmd("ingest").action((_, c) => c.copy(cmd = "ingest"))
      .text("Connect to a live market data source and record all received data.")
      .children(
        opt[Seq[String]]('s', "sources")
          .action((x, c) => c.copy(sources = x))
          .text("Comma separated list of data sources to ingest"),
        opt[Seq[String]]("topics")
          .action((x, c) => c.copy(topics = x))
          .text("Comma separated list of topics to ingest"),
        opt[Seq[String]]("types")
          .action((x, c) => c.copy(types = x))
          .text("Comma separated list of data types to ingest")
      )

    cmd("server").action((_, c) => c.copy(cmd = "server"))
      .text("Start a local trading server.")
      .children(
        opt[Int]('p', "port")
          .action((x, c) => c.copy(port = x))
          .text("Which port to expose the HTTP API on. Defaults to 9020.")
      )

    help("help").text("Prints this usage text")

    checkConfig(c =>
      if (c.cmd.isEmpty) failure("enter a command")
      else success)
  }

  private var metricsServer: Option[HTTPServer] = None

//  val gauge = Gauge.build("market_data_event_buffer_length", "buffer length")
//    .labelNames("product")
//    .register()

  def main(args: Array[String]): Unit = {
    import io.circe.generic.auto._
    import io.circe.syntax._

    val opts = optsParser.parse(args, Opts()).get

    val baseConfigJson: Json = parse(Source
      .fromInputStream(getClass.getResourceAsStream("/base_config.json"))
      .getLines.mkString).right.get

    val flashbotConfig = baseConfigJson
      .deepMerge(parse(Source.fromFile(opts.config, "utf-8").getLines.mkString).right.get)
      .as[ConfigFile].right.get

    val systemConfig = ConfigFactory.load()
      .withValue("akka.persistence.journal.leveldb.dir",
        ConfigValueFactory.fromAnyRef(s"${opts.dataPath}/journal"))
      .withValue("akka.persistence.snapshot-store.local.dir",
        ConfigValueFactory.fromAnyRef(s"${opts.dataPath}/snapshot-store"))

    implicit val system: ActorSystem = ActorSystem("flashbot", systemConfig)
    implicit val mat: Materializer = Utils.buildMaterializer
    implicit val ec: ExecutionContext = system.dispatcher

    if (opts.metricsPort != 0)
      metricsServer = Some(new HTTPServer(opts.metricsPort))

    opts.cmd match {
      case "ingest" =>
        val srcNames =
          if (opts.sources.isEmpty) flashbotConfig.data_sources.keySet
          else opts.sources
        srcNames.foreach(srcName => {
          val actor = system.actorOf(Props[IngestService], s"ingest:$srcName")
          actor ! (
            srcName,
            List(opts.dataPath, "sources").mkString("/"),
            flashbotConfig.data_sources(srcName),
            opts.topics.toSet,
            opts.types.toSet
          )
        })

      case "server" =>
        val engine = system.actorOf(
          Props(new TradingEngine(
            List(opts.dataPath, "sources").mkString("/"),
            flashbotConfig.strategies,
            flashbotConfig.data_sources,
            flashbotConfig.exchanges)),
          "trading-engine"
        )
        Http().bindAndHandle(api.routes(engine), "localhost", opts.port)
    }

  }
}
