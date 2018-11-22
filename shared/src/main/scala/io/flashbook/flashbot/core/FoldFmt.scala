package io.flashbook.flashbot.core

trait FoldFmt[T] {
  def fold(x: T, y: T): T
  def unfold(x: T): (T, Option[T])
}
