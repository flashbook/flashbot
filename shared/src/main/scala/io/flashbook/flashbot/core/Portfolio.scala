package io.flashbook.flashbot.core

/**
  * Keeps track of asset balances and positions across all exchanges. Calculates equity and PnL.
  */
case class Portfolio(assets: Map[Account, Double],
                     positions: Map[Market, Position]) {

  /**
    * Equity is the sum of the `targetAsset` values of all positions.
    */
  def equity(targetAsset: String, prices: PriceMap): Double =
    positions.values.map(_.value(targetAsset, prices)).sum

  def position(account: Account): Option[Position] = positions.get(account)

  def withPosition(account: Account, position: Position): Portfolio =
    positions + (account -> position)

  def updatePosition(account: Account, fn: Position => Position): Portfolio =
    withPosition(account, fn(position(account)))

  def filter(fn: ((Account, Position)) => Boolean): Portfolio = positions.filter(fn)

  def close(markets: Seq[Market], priceMap: PriceMap): Portfolio = ???

  /**
    * Splits each account's total equity/buying power evenly among all given markets.
    */
  def isolatedBuyingPower(markets: Seq[Market],
                          priceMap: PriceMap,
                          equityDenomination: String): Map[Market, Double] = {
    // First close all positions.
    val closed = this.close(markets, priceMap)

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
  implicit def portfolio(balances: Map[Account, Position]): Portfolio = new Portfolio(balances)

//  sealed trait Scope
//  case object All extends Scope
//  case class Assets(assets: Set[String], withPegged: Boolean) extends Scope
}
