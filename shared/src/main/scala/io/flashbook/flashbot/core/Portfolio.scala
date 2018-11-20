package io.flashbook.flashbot.core

import io.circe.{Decoder, Encoder, KeyEncoder}
import io.flashbook.flashbot.core.Instrument.CurrencyPair
import io.flashbook.flashbot.core._
import io.circe.generic.semiauto._
import io.circe.generic.auto._

/**
  * Keeps track of asset balances and positions across all exchanges. Calculates equity and PnL.
  */
case class Portfolio(assets: Map[Account, Double],
                     positions: Map[Market, Position]) {

  def balance(account: Account): Double = assets.getOrElse(account, 0.0)

  def withBalance(account: Account, balance: Double): Portfolio =
    copy(assets = assets + (account -> balance))

  /**
    * Equity is the sum of the `targetAsset` values of all positions.
    */
  def equity(targetAsset: String, prices: PriceMap): Double =
    positions.values.map(_.value(targetAsset, prices)).sum

  def position(market: Market): Option[Position] = positions.get(market)

  def withPosition(market: Market, position: Position): Portfolio =
    copy(positions = positions + (market -> position))

  def updatePosition(market: Market, fn: Position => Position): Portfolio =
    withPosition(market, fn(position(market)))

  def closePositions(markets: Seq[Market], priceMap: PriceMap): Portfolio = ???

  def closePositions(priceMap: PriceMap): Portfolio =
    closePositions(positions.keys.toSeq, priceMap)

  /**
    * Splits each account's total equity/buying power evenly among all given markets.
    */
  def isolatedBuyingPower(markets: Seq[Market],
                          priceMap: PriceMap,
                          equityDenomination: String): Map[Market, Double] = {
    // First close all positions.
    val closed = this.closePositions(priceMap)

    // Calculate total equity per account.
    val accountEquities: Map[Account, Double] =
      closed.positions.mapValues(_.value(equityDenomination, priceMap))

    // Distribute between all markets of account.
    accountEquities.flatMap { case (account, buyingPower) =>
      val accountMarkets = markets.filter(_.instrument.settledIn == account.security)
      accountMarkets.map(inst => inst -> buyingPower / accountMarkets.size)
    }
  }

  /**
    * Splits each account's total selling power (like buying power, but for shorts) evenly among
    * all given markets that support shorting. Use `0` for all others.
    */
  def isolatedSellingPower(markets: Seq[Market],
                           priceMap: PriceMap,
                           equityDenomination: String): Map[Market, Double] = {
    ???
  }
}

object Portfolio {
  implicit val portfolioEn: Encoder[Portfolio] = deriveEncoder
  implicit val portfolioDe: Decoder[Portfolio] = deriveDecoder
}
