package core

import io.circe.Json
import core.Utils.parseProductId
import core.DataSource.{Address, DataSourceConfig}
import core.Report.{TimeSeriesCandle, TimeSeriesEvent}
import core.TradingSession.{OrderTarget, SessionReportEvent}

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
  def initialize(params: Json,
                 dataSourceConfig: Map[String, DataSourceConfig],
                 initialBalances: Map[Account, Double]): List[String]

  /**
    * Receives streaming streaming market data from the sources declared during initialization.
    */
  def handleData(data: MarketData)(implicit ctx: TradingSession)

  /**
    * Receives events that occur in the system as a result of actions taken in this strategy.
    */
  def handleEvent(event: StrategyEvent)(implicit ctx: TradingSession): Unit = {}

  def orderTargetRatio(exchangeName: String,
                       product: String,
                       ratio: Double)
                      (implicit ctx: TradingSession): Unit = {
    ctx.handleEvents(OrderTarget(exchangeName, ratio, parseProductId(product), None))
  }

  def orderTargetRatio(exchangeName: String,
                       product: String,
                       ratio: Double,
                       price: Double,
                       name: String)
                      (implicit ctx: TradingSession): Unit = {
    ctx.handleEvents(OrderTarget(exchangeName, ratio, parseProductId(product), Some(name, price)))
  }

  def record(name: String, value: Double, micros: Long)
            (implicit ctx: TradingSession): Unit = {
    ctx.handleEvents(SessionReportEvent(TimeSeriesEvent(name, value, micros)))
  }

  def record(name: String, candle: Candle)
            (implicit ctx: TradingSession): Unit = {
    ctx.handleEvents(SessionReportEvent(TimeSeriesCandle(name, candle)))
  }

  def resolveAddress(address: Address): Option[Iterator[MarketData]] = None
}

object Strategy {
  final case class StrategyConfig()
}
