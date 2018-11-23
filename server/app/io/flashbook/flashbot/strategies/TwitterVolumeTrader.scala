package io.flashbook.flashbot.strategies

import io.flashbook.flashbot.core.DataSource.DataSourceConfig
import io.flashbook.flashbot.core._
import io.circe.Json
import io.flashbook.flashbot.engine.TradingSession

import scala.concurrent.Future

/**
  * This strategy is an example of how to base a strategy off of an external data feed, volume of
  * Tweets in this case.
  */
class TwitterVolumeTrader extends Strategy {
  override def title: String = "Twitter Volume Trader"
  override def initialize(params: Json,
                          portfolio: Portfolio,
                          loader: SessionLoader): Future[Seq[String]] = ???

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = ???
}
