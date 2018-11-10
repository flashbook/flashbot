package io.flashbook.flashbot.strategies

import io.flashbook.flashbot.core.DataSource.DataSourceConfig
import io.flashbook.flashbot.core.{Account, MarketData, Strategy}
import io.circe.Json
import io.flashbook.flashbot.engine.TradingSession

/**
  * Do you have a magical feed of buy/sell signals? This strategy simply turns those signals into
  * orders on an exchange as quickly as possible.
  */
class ExternalSignal extends Strategy {
  override def title: String = "External Signal"

  override def initialize(jsonParams: Json,
                          dataSourceConfig: Map[String, DataSourceConfig],
                          initialBalances: Map[Account, Double]): List[String] = ???

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = ???
}
