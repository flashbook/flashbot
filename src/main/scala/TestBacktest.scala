import java.util.concurrent.Executors

import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Keep, Sink, Source}
import core.DataSource.{DataSourceConfig, DataTypeConfig}
import core.Exchange.ExchangeConfig
import core.{Account, TimeRange, Utils}
import core.TradingSession.{Backtest, SessionActor}
import io.circe.literal._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object TestBacktest extends App {


  class TestSessionActor extends Actor {
    implicit val ec: ExecutionContext =
      ExecutionContext.fromExecutor(Executors.newFixedThreadPool(100))
    implicit val sys: ActorSystem = context.system
    implicit val mat: Materializer = Utils.buildMaterializer

    override def receive: Receive = {
      case "start" =>
        val source = Source.unfoldAsync(0L) {
          case 5L => Future { None }
          case x => Future {
            Thread.sleep(200)
            Some(x + 1, "ev_" + x.toString)
          }
        }

        val fut = source.toMat(Sink.foreach {
          ev =>
            println(ev)
        })(Keep.right).run

        fut onComplete {
          case Success(foo) =>
            println(foo)
          case Failure(err) =>
            println("err", err)
        }
    }
  }



  val system = ActorSystem("test-system")

  val outer = system.actorOf(Props[Outer], "outer-actor")

  val actor = system.actorOf(Props[TestSessionActor])

//  val actor = system.actorOf(Props(new SessionActor(
//    "flashbot_data/sources",
//    Map("secret_d" -> "strategies.SecretD"),
//    Map("bitstamp" -> DataSourceConfig("sources.BitstampMarketDataSource",
//      Map("btc_usd" -> json"{}"), Map("trades" -> DataTypeConfig("90d")))),
//    Map("bitstamp" -> ExchangeConfig("exchanges.Bitstamp", json"{}")),
//    "secret_d",
//    json"""{"exchange" : "bitstamp",
//      "bar_size" : "1m",
//      "product" : "btc_usd"
//      }""",
//    Backtest(TimeRange(1535831721000000L, 1535831721000000L + 1000000 * 60 * 60 * 5)),
//    outer,
//    Map(Account("bitstamp", "usd") -> 1000L)
//  )))

  class Outer extends Actor {
    override def receive: Receive = {
      case "doit" =>
        actor ! "start"

      case msg =>
        println("outer", msg)
    }
  }

  outer ! "doit"
}
