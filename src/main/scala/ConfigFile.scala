import core.{DataSource, Exchange, Strategy}
import io.circe.generic.auto._

case class ConfigFile(api_key: String = "",
                      strategies: Map[String, String] = Map.empty,
                      exchanges: Map[String, Exchange.ExchangeConfig] = Map.empty,
                      data_sources: Map[String, DataSource.DataSourceConfig] = Map.empty)

