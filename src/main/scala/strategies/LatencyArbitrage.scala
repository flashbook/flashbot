package strategies

import core.{MarketData, Strategy, TradingSession}
import io.circe.Json

class LatencyArbitrage extends Strategy {
  override def title: String = "Latency Arbitrage"

  override def initialize(jsonParams: Json): List[String] = ???

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = ???
}
