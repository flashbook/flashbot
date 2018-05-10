package strategies

import core.{MarketData, Strategy, TradingSession}

/**
  * This strategy is an example of how to base a strategy off of an external data feed, volume of
  * Tweets in this case.
  */
class TwitterVolumeTrader extends Strategy {
  override def title: String = "Twitter Volume Trader"
  override def initialize(implicit ctx: TradingSession): Unit = ???

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = ???
}
