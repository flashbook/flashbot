package io.flashbook.flashbot

import io.circe.{Decoder, Encoder}

package object service {
  import io.flashbook.flashbot.core.DataSource._
  import io.flashbook.flashbot.core.Exchange._
  import io.flashbook.flashbot.core._

  import io.circe.generic.auto._
  import io.circe.generic.semiauto._

  case class ConfigFile(api_key: String = "",
                        strategies: Map[String, String] = Map.empty,
                        exchanges: Map[String, ExchangeConfig] = Map.empty,
                        data_sources: Map[String, DataSourceConfig] = Map.empty,
                        currencies: Map[String, CurrencyConfig] = Map.empty,
                        bots: Map[String, BotConfig] = Map.empty)
  object ConfigFile {
    implicit def configFileEn: Encoder[ConfigFile] = deriveEncoder[ConfigFile]
    implicit def configFileDe: Decoder[ConfigFile] = deriveDecoder[ConfigFile]
  }
}
