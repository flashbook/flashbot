package io.flashbook.flashbot.core

import io.flashbook.flashbot.core.Order.{Buy, Sell, Side}

case class FixedSize(amount: Double, security: String) {

  def isEmpty = amount == 0

  def side: Side = {
    if (amount > 0) Buy
    else Sell
  }
}

object FixedSize {
//  implicit def buildFixedSizeDouble(amount: Double): FixedSize = FixedSize(amount, None)
//  implicit def buildFixedSizeInt(amount: Int): FixedSize = FixedSize(amount, None)
//  implicit def buildFixedSizeAmount(amount: Long): FixedSize = FixedSize(amount, None)
  implicit def buildFixedSizeStr(str: String): FixedSize = {
    val parts = str.trim.split(" ")
    val symbol = parts.tail.mkString("")
    FixedSize(parts.head.toDouble, symbol)
  }
  implicit def buildFixedSizeFromTuple(tuple: (Double, String)): FixedSize =
    FixedSize(tuple._1, tuple._2)

  def apply(amount: Double, symbol: String): FixedSize =
    new FixedSize(amount, symbol)
}
