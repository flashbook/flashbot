package io.flashbook.flashbot.strategies

import io.flashbook.flashbot.core.DataSource.DataSourceConfig
import io.flashbook.flashbot.core.{Account, MarketData, Strategy, TradingSession}
import io.circe.Json

class LatencyArbitrage extends Strategy {
  override def title: String = "Latency Arbitrage"

  override def initialize(jsonParams: Json,
                          dataSourceConfigs: Map[String, DataSourceConfig],
                          initialBalances: Map[Account, Double]): List[String] = ???

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = ???
}
