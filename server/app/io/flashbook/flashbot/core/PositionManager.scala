package io.flashbook.flashbot.core

/**
  * PositionManager is a set of functions that analyze a portfolio to answer questions about
  * positions. E.g. What is our current position in asset A? What trades are necessary to achieve
  * a position of X in asset B with the constraints X, Y, and Z?
  */
object PositionManager {

  /**
    * ------------------------------------
    *  Position Percent Group Calculation
    * ------------------------------------
    *
    *   Suppose the following portfolio, which is net long Bitcoin $21k:
    *
    *     portfolio = Map(
    *       Bitmex:XBTUSD -> -1k,      // (XBT, $-1k, +2k PNL, 4x leverage)
    *       Bitmex:ETHUSD -> 0,        // (ETH, $0, 2x leverage)
    *       Bitmex:XBT    -> 2,        // (XBT, $16k)
    *       Binance:BTC   -> 0.5       // (BTC, $4k)
    *     )
    *
    *     Overall BTC position: $4k + ($16k + $2k) - $1k = $21k
    *     Overall ETH position: $0k
    *
    *   We'd like to make trades on Bitmex (but not Binance) so that our overall portfolio is
    *   long Bitcoin and short Ethereum by the same amount in USD and that each of these positions
    *   is only half of what they could be within the same constraints. In this case we'd call:
    *
    *     percentGroup(portfolio,
    *       (Bitmex:XBTUSD, Bitmex:ETHUSD),
    *       (btc -> 0.5, eth -> -0.5)
    *     )
    *
    *   Which would return something along the lines of:
    *
    *     Map(
    *       Bitmex:XBTUSD -> 4k USD,
    *       Bitmex:ETHUSD -> -8k USD
    *     )
    *
    *
    *  1. Isolated buying/selling power per market
    * ----------------------------------------------
    *
    *   Our first step is to determine our buying/selling power isolated per market.
    *
    *     Bitmex:XBT wallet balance: 2 XBT = $16k
    *     Bitmex:XBTUSD PNL = $2k
    *     Bitmex:XBT account equity = $18k
    *
    *     Bitmex:XBTUSD (4x) buying/selling power = $72k
    *     Bitmex:ETHUSD (2x) buying/selling power = $36k
    *
    *
    *  2. Solve for positions
    * -------------------------
    *
    * Bitmex:XBT      <----------------------------------- | -- XB ------------------------------->
    *
    * Bitmex:XBTUSD   <----------------------------------- | ------- XU -------------------------->
    *
    * Bitmex:ETHUSD   <------------------ EU ------------- | ------------------------------------->
    *
    * Binance:BTC     <----------------------------------- | ----- 4k ---------------------------->
    *
    * Net:BTC         <----------------------------------- | ------------- NB ------------ MaxP -->
    *
    * Net:ETH         <------------------ NE ------------- | ----------------------------- MaxP -->
    *
    *
    *   XB = XBT wallet balance USD value
    *   XU = XBTUSD contracts
    *   EU = ETHUSD contracts
    *   MaxP = Max net position per asset in USD (percent 1.0)
    *   NB = Net BTC position in USD
    *   NE = Net ETH position in USD
    *
    *
    *   Where:
    *     XB > 0
    *     $-72k <= XU <= $72k
    *     $-36k <= EU <= $36k
    *     XU/4 + EU/2 <= $18k
    *
    *     NB = 4k + XU + XB
    *     NE = EU
    *     NB = .5 * MaxP
    *     NE = -.5 * MaxP
    *
    *   First solve for MaxP. Then calculate NB and NE, and solve for the rest of the variables.
    *
    *
    * @param portfolio our whole portfolio
    * @param markets the markets that we'd like to calculate positions for
    * @param targetPercents a map of assets to the desired position percent
    * @param priceMap market prices
    * @param equityDenomination what is buying power equity in terms of? Defaults to USD.
    * @return a map of notional position values per market that satisfy the given percentages.
    */
  def percentGroup(portfolio: Portfolio,
                   markets: Seq[Market],
                   targetPercents: Map[String, Double],
                   priceMap: PriceMap,
                   equityDenomination: String = "usd"): Map[Market, Double] = {
    // TODO: Implement this with Choco Solver
    ???
  }
}
