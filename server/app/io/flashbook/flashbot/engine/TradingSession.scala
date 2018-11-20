package io.flashbook.flashbot.engine

import io.circe.Json
import io.flashbook.flashbot.core.Action.ActionQueue
import io.flashbook.flashbot.core.DataSource._
import io.flashbook.flashbot.core._
import io.flashbook.flashbot.report.{Report, ReportDelta, ReportEvent}


trait TradingSession {
  def id: String
  def send(events: Any*): Unit
  def getPortfolio: Portfolio
  def getActionQueues: Map[String, ActionQueue]
  def instruments: InstrumentIndex
}

object TradingSession {

  trait Event
  case class LogMessage(message: String) extends Event
  case class OrderTarget(exchangeName: String,
                         targetId: TargetId,
                         size: FixedSize,
                         price: Option[Double],
                         postOnly: Boolean) extends Event
  case class SessionReportEvent(event: ReportEvent) extends Event


  sealed trait Mode
  case class Backtest(range: TimeRange) extends Mode
  case object Paper extends Mode
  case object Live extends Mode

  object Mode {
    def apply(str: String): Mode = str match {
      case "live" => Live
      case "paper" => Paper
    }
  }

  case class TradingSessionState(id: String,
                                 strategy: String,
                                 strategyParams: Json,
                                 mode: Mode,
                                 startedAt: Long,
                                 portfolio: Portfolio,
                                 report: Report) {
    def updateReport(delta: ReportDelta): TradingSessionState =
      copy(report = report.update(delta))
  }

  case class SessionSetup(instruments: Map[String, Map[String, Instrument]],
                          dataSourceAddresses: Seq[Address],
                          dataSources: Map[String, DataSource],
                          exchanges: Map[String, Exchange],
                          strategy: Strategy,
                          sessionId: String,
                          sessionMicros: Long)

  def closeActionForOrderId(actions: ActionQueue, ids: IdManager, id: String): ActionQueue =
    actions match {
      case ActionQueue(Some(action), _) if ids.actualIdForTargetId(action.targetId) == id =>
        actions.closeActive
      case _ => actions
    }
}
