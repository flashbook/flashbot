package core

import io.circe.Json
import Strategy._

/**
  * Strategy is a container of logic that describes the behavior and data dependencies of a trading
  * strategy. We interact with the outer Flashbot system (placing orders, logging, plotting, etc..)
  * via the TradingContext, which processes all strategy output/side effects as an event stream.
  * This design is intended to make it easier for us to support remote strategies in the future,
  * possibly written in other languages.
  */
abstract class Strategy {
  /**
    * Human readable title for display purposes.
    */
  def title: String

  /**
    * During initialization, strategies declare what data sources they need by name, all of which
    * must be registered in the system or an error is thrown. If all is well, the data sources are
    * loaded and are all queried for a certain time period and results are merged and streamed into
    * the `handleData` method. Each stream should complete when there is no more data, which auto
    * shuts down the strategy when all data streams complete.
    */
  def initialize(params: Json): List[String]

  /**
    * Receives streaming streaming market data from the sources declared during initialization.
    */
  def handleData(data: MarketData)(implicit ctx: TradingSession)

  def orderTargetRatio(target: Ratio)(implicit ctx: TradingSession): Unit = {
  }

  def orderTargetRatio(target: Ratio, price: Double, name: String = "limit")
                      (implicit ctx: TradingSession): Unit = {
  }
}

object Strategy {
  final case class StrategyConfig()
}
