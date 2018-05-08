package core

import io.circe.Json

/**
  * Strategy is a container of logic that describes the behavior and data dependencies of a trading
  * strategy. We interact with the outer Flashbot system (placing orders, logging, plotting, etc..)
  * via the TradingContext, which processes all strategy output/side effects as an event stream.
  * This design is intended to make it easier for us to support remote strategies in the future,
  * possibly written in other languages.
  */
trait Strategy {
  def name: String

  /**
    * During initialization, strategies declare what data sources they need by name, all of which
    * must be registered in the system or an error is thrown. If all is well, the data sources are
    * loaded and are all queried for a certain time period and results are merged and streamed into
    * the `handleData` method. Each stream should complete when there is no more data, which auto
    * shuts down the strategy when all data streams complete.
    */
  def initialize(params: Json)(implicit ctx: TradingSession): List[String]

  /**
    * Receives streaming streaming market data from the sources declared during initialization.
    */
  def handleData(data: MarketData)(implicit ctx: TradingSession)
}

object Strategy {
  final case class StrategyConfig()

  abstract class PureStrategy extends Strategy {
    protected def orderTargetPercent(target: Double)(implicit ctx: TradingSession): Unit = {
    }
  }

  abstract class BaseStrategy extends PureStrategy {
    override def orderTargetPercent(target: Double)(implicit ctx: TradingSession): Unit =
      super.orderTargetPercent(target)
  }
}
