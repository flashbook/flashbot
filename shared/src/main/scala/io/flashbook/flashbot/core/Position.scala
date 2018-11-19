package io.flashbook.flashbot.core

case class Position(size: Double, leverage: Double, entryPrice: Double) {
  /**
    * How much is this position worth in terms of `targetAsset`?
    */
  def value(targetAsset: String, prices: PriceMap): Double = {
  }
}
