package strategies

import core.{MarketData, Strategy, TradingSession}

class LatencyArbitrage extends Strategy {
  override def title: String = "Latency Arbitrage"

  override def initialize(implicit ctx: TradingSession): Unit = ???

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = ???
}
