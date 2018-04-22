import scopt.OptionParser
import java.io.File

import akka.actor.{ActorSystem, Props}
import akka.stream.{ActorMaterializer, Materializer}
import collectors.GdaxMarketDataCollector
import io.prometheus.client.exporter.HTTPServer

import scala.concurrent.ExecutionContext

object FlashbotMain {

  case class Opts(dataPath: String = "flash_data",
                  logPath: String = "flash_logs",
                  paperTrading: Boolean = true,
                  port: Int = 9020,
                  metricsPort: Int = 0,
                  cmd: String = "")

  private val optsParser = new OptionParser[Opts]("flashbot") {
    head("flashbot", "0.1")

    opt[String]('d', "dataPath").action((x, c) => c.copy(dataPath = x))
    opt[String]('l', "logPath").action((x, c) => c.copy(logPath = x))

    opt[Int]('m', "metricsPort")
      .action((x, c) => c.copy(metricsPort = x))
      .text("Which port to expose the Prometheus metrics server on. Defaults to 0 (off)")

    cmd("collect").action((_, c) => c.copy(cmd = "collect"))
      .text("Connect to a live market data source and record all received data.")

    cmd("run").action((_, c) => c.copy(cmd = "run"))
      .text("Start a local trading server for running strategies in a (live or simulated) environment.")
      .children(
        opt[Boolean]("paper")
          .action((x, c) => c.copy(paperTrading = x))
          .text("Whether this. Defaults to true."),
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

  implicit val system: ActorSystem = ActorSystem("flashbot")
  implicit val mat: Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  def main(args: Array[String]): Unit = {
    val opts = optsParser.parse(args, Opts()).get

    // Optionally start this service's metrics server.
    if (opts.metricsPort != 0)
      metricsServer = Some(new HTTPServer(opts.metricsPort))

    opts.cmd match {
      case "collect" =>
        system.actorOf(Props[GdaxMarketDataCollector], "data-collector")

      case "run" =>
        println(if (opts.paperTrading) "paper trading" else "real life trading!")
    }
  }
}
