package io.flashbook.flashbot.core

import io.circe.{Decoder, Encoder}

trait FoldFmt[T] {
  def fold(x: T, y: T): T
  def unfold(x: T): (T, Option[T])

  def modelEn: Encoder[T]
  def modelDe: Decoder[T]
}
