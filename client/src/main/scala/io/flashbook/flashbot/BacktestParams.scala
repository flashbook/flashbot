package io.flashbook.flashbot

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

object BacktestParams {

  case class StratParams(foo: String)
  implicit val paramsEn: Encoder[StratParams] = deriveEncoder[StratParams]
  implicit val paramsDe: Decoder[StratParams] = deriveDecoder[StratParams]

}
