package io.flashbook.flashbot.core

import io.circe.{Decoder, Encoder}

trait CanDeltaUpdate {
  type Delta
  def update(delta: Delta): this.type
  def deltaEncoder: Encoder[Delta]
  def deltaDecoder: Decoder[Delta]
}

