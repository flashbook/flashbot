package strategies

import core.{MarketData, Strategy, TradingSession}
import io.circe.Json

/**
  * This strategy is an example of how to base a strategy off of an external data feed, volume of
  * Tweets in this case.
  */
class TwitterVolumeTrader extends Strategy {
  override def title: String = "Twitter Volume Trader"
  override def initialize(params: Json): List[String] = ???

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = ???
}
