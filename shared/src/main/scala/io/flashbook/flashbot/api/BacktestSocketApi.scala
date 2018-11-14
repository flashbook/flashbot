package io.flashbook.flashbot.api


import io.circe._
import io.circe.generic.semiauto._
import io.flashbook.flashbot.report._


object BacktestSocketApi {

  sealed trait Req
  case class RunBacktest(params: Json) extends Req

  implicit val reqEn: Encoder[Req] = deriveEncoder[Req]
  implicit val reqDe: Decoder[Req] = deriveDecoder[Req]

  case class InitialReport(report: Report)
  case class ReportUpdate(delta: Json)
  case class FinalReport(report: Report)
  case class Err(msg: String)

}
