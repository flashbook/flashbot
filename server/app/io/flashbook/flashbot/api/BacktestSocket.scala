package io.flashbook.flashbot.api

import io.flashbook.flashbot.util.json._
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import io.flashbook.flashbot.core.TimeRange
import io.flashbook.flashbot.engine.TradingEngine.{BacktestQuery, ReportResponse}
import io.flashbook.flashbot.service.Control
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import io.circe.Json
import io.circe.generic.auto._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * A one-time use WebSocket. Send it params, receive a report stream. The connection closes after
  * the backtest is finished.
  */
object BacktestSocket {
  def props(strategy: String, timeRange: TimeRange, balances: String, out: ActorRef) =
    Props(new BacktestSocket(strategy, timeRange, balances, out))
}

class BacktestSocket(strategy: String, timeRange: TimeRange, balances: String, out: ActorRef) extends Actor {
  import BacktestSocketApi._
  import io.flashbook.flashbot.report._

  implicit val timeout: Timeout = Timeout(5 minutes)
  implicit val ec: ExecutionContext = context.dispatcher

  override def receive = {
    // We get a report when the backtest just started.
    case report: Report =>
      out ! InitialReport(report)

    // We pipe this to ourselves when the backtest is done.
    case fr: FinalReport =>
      out ! fr
      self ! PoisonPill

    // We requested report deltas so the js client can reconstruct report.
    case delta: Json =>
      out ! ReportUpdate(delta)

    case msg: String =>
      parseJson[Req](msg) match {
        case Right(RunBacktest(params)) =>
          val query = BacktestQuery(strategy, printJson(params), timeRange, balances,
            None, reportUpdates = true)
          println("SENDING QUERY")
          println(query)
          (Control.engine.get ? query)
            .mapTo[ReportResponse]
            .map(rr => FinalReport(rr.report)) pipeTo self

        case Left(err) => Err(err)
      }
  }
}
