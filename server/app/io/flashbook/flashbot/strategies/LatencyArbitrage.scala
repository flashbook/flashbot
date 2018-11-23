package io.flashbook.flashbot.strategies

import io.flashbook.flashbot.core.DataSource.DataSourceConfig
import io.flashbook.flashbot.core._
import io.circe.Json
import io.flashbook.flashbot.engine.TradingSession

import scala.concurrent.Future

class LatencyArbitrage extends Strategy {
  override def title: String = "Latency Arbitrage"

  override def initialize(jsonParams: Json,
                          portfolio: Portfolio,
                          loader: SessionLoader): Future[Seq[String]] = ???

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = ???
}
