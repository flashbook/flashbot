package io.flashbook.flashbot.core

import io.circe.{Decoder, Encoder, KeyEncoder}
import io.circe.generic.semiauto._

/**
  * Keeps track of asset balances and positions across all exchanges. Calculates equity and PnL.
  */
case class Portfolio(assets: Map[Account, Double],
                     positions: Map[Market, Position]) {

  def balance(account: Account): Double = assets.getOrElse(account, 0.0)

  def withBalance(account: Account, balance: Double): Portfolio =
    copy(assets = assets + (account -> balance))

  def updateBalance(account: Account, fn: Double => Double): Portfolio =
    withBalance(account, fn(balance(account)))

  /**
    * How much money do we have in terms of `targetAsset`?
    */
  def equity(targetAsset: String = "usd")(implicit prices: PriceIndex): Double = ???

  def position(market: Market): Option[Position] = positions.get(market)

  def setPositionSize(market: Market, size: Long)
                     (implicit instruments: InstrumentIndex,
                      prices: PriceIndex): Portfolio = {
    val instrument = instruments(market)
    val account = Account(market.exchange, instrument.symbol)
    val (newPosition, pnl) = positions(market).setSize(size, instrument, prices(market))
    unsafeSetPosition(market, newPosition)
      .withBalance(account, assets(account) + pnl)
  }

  def closePosition(market: Market)
                   (implicit instruments: InstrumentIndex,
                    prices: PriceIndex): Portfolio =
    setPositionSize(market, 0)

  def closePositions(markets: Seq[Market])
                    (implicit instruments: InstrumentIndex,
                     prices: PriceIndex): Portfolio =
    markets.foldLeft(this)(_.closePosition(_))

  def closePositions(implicit instruments: InstrumentIndex,
                     prices: PriceIndex): Portfolio =
    closePositions(positions.keys.toSeq)

  // This is unsafe because it lets you set a new position without updating account
  // balances with PNL.
  def unsafeSetPosition(market: Market, position: Position): Portfolio =
    copy(positions = positions + (market -> position))

  /**
    * Splits each account's total equity/buying power evenly among all given markets.
    */
//  def isolatedBuyingPower(markets: Seq[Market],
//                          priceMap: PriceMap,
//                          equityDenomination: String): Map[Market, Double] = {
//    // First close all positions.
//    val closed = this.closePositions(priceMap)
//
//    // Calculate total equity per account.
//    val accountEquities: Map[Account, Double] =
//      closed.positions.mapValues(_.value(equityDenomination, priceMap))
//
//    // Distribute between all markets of account.
//    accountEquities.flatMap { case (account, buyingPower) =>
//      val accountMarkets = markets.filter(_.instrument.settledIn == account.security)
//      accountMarkets.map(inst => inst -> buyingPower / accountMarkets.size)
//    }
//  }

}

object Portfolio {
  implicit val portfolioEn: Encoder[Portfolio] = deriveEncoder
  implicit val portfolioDe: Decoder[Portfolio] = deriveDecoder
}
