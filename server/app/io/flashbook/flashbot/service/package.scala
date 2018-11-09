package io.flashbook.flashbot

package object service {
  import io.flashbook.flashbot.core.DataSource._
  import io.flashbook.flashbot.core.Exchange._
  import io.flashbook.flashbot.core._

  case class ConfigFile(api_key: String = "",
                        strategies: Map[String, String] = Map.empty,
                        exchanges: Map[String, ExchangeConfig] = Map.empty,
                        data_sources: Map[String, DataSourceConfig] = Map.empty,
                        currencies: Map[String, CurrencyConfig] = Map.empty,
                        bots: Map[String, BotConfig] = Map.empty)
}
