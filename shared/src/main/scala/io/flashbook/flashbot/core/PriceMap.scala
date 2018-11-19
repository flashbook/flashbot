package io.flashbook.flashbot.core

/**
  * The data structure used to calculate positions, equity, PnL, order sizes, etc... This will
  * typically be populated from the last trade price.
  */
class PriceMap(val prices: Map[Market, Double]) extends AnyVal {
//  def convert(account: Account, value: Double, targetAsset: String): Double = ???

//  def filterBase(fn: String => Boolean): PriceMap = prices.filterKeys(p => fn(p.product.base))
//  def filterQuote(fn: String => Boolean): PriceMap = prices.filterKeys(p => fn(p.product.quote))
}

object PriceMap {
  implicit def priceMap(prices: Map[Market, Double]): PriceMap = new PriceMap(prices)
}
