package io.flashbook.flashbot.core

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

case class Position(size: Double, leverage: Double, entryPrice: Double) {
  /**
    * How much is this position worth in terms of `targetAsset`?
    */
  def value(targetAsset: String, prices: PriceMap): Double = ???
}

object Position {
  implicit val postionEn: Encoder[Position] = deriveEncoder
  implicit val postionDe: Decoder[Position] = deriveDecoder
}
