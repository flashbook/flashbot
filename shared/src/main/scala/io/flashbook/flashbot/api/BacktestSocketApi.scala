package io.flashbook.flashbot.api

import io.circe._
import io.circe.generic.auto._
import io.flashbook.flashbot.report._

object BacktestSocketApi {

  sealed trait BacktestSocketReq
  object BacktestSocketReq {
    case class RunBacktest(params: Json) extends BacktestSocketReq
  }


  sealed trait BacktestSocketRsp
  object BacktestSocketRsp {
    case class InitialReport(report: Report) extends BacktestSocketRsp
    case class ReportUpdate(delta: Json) extends BacktestSocketRsp
    case class FinalReport(report: Report) extends BacktestSocketRsp
    case class Err(msg: String) extends BacktestSocketRsp
  }

}
