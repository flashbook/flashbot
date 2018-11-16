package io.flashbook.flashbot.core

import java.util.concurrent.Future

import io.circe._
import io.flashbook.flashbot.core.DataSource.{Address, DataSourceConfig}
import io.flashbook.flashbot.engine.TradingSession
import io.flashbook.flashbot.engine.TradingSession.{OrderTarget, SessionReportEvent, SetHedge}
import io.flashbook.flashbot.report.ReportEvent._
import io.flashbook.flashbot.util.parseProductId

/**
  * Strategy is a container of logic that describes the behavior and data dependencies of a trading
  * strategy. We interact with the outer Flashbot system (placing orders, logging, plotting, etc..)
  * via the TradingContext, which processes all strategy output/side effects as an event stream.
  * This design is intended to make it easier for us to support remote strategies in the future,
  * possibly written in other languages.
  */
abstract class Strategy {

  val DEFAULT = "default"

  /**
    * Human readable title for display purposes.
    */
  def title: String

  /**
    * Generate a self-describing StrategyInfo instance given the FlashbotScope in which this
    * strategy will run.
    */
  def info(loader: FlashbotScope): Future[StrategyInfo]

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

  /**
    * Receives commands that occur from outside of the system, such as from the UI or API.
    */
  def handleCommand(command: StrategyCommand)(implicit ctx: TradingSession): Unit = {}

  def orderTargetRatio(exchangeName: String,
                       product: String,
                       ratio: Double,
                       key: String = DEFAULT,
                       price: Option[Double] = None,
                       scope: Scope = PairScope,
                       postOnly: Boolean = false)
                      (implicit ctx: TradingSession): Unit = {
    ctx.handleEvents(OrderTarget(
      exchangeName,
      TargetId(parseProductId(product), key),
      Ratio(ratio, scope),
      price,
      postOnly
    ))
  }

  def order(exchangeName: String,
            product: String,
            amount: Double,
            key: String = DEFAULT,
            price: Option[Double] = None,
            postOnly: Boolean = false)
           (implicit ctx: TradingSession): Unit = {
    ctx.handleEvents(OrderTarget(
      exchangeName,
      TargetId(parseProductId(product), key),
      Amount(amount),
      price,
      postOnly
    ))
  }

  def orderNotional(exchangeName: String,
                    product: String,
                    funds: Double,
                    key: String = DEFAULT,
                    price: Option[Double] = None,
                    postOnly: Boolean = false)
                   (implicit ctx: TradingSession): Unit = {
    ctx.handleEvents(OrderTarget(
      exchangeName,
      TargetId(parseProductId(product), key),
      Funds(funds),
      None,
      postOnly
    ))
  }

  def setHedge(coin: String, position: Long)
              (implicit ctx: TradingSession): Unit = {
    ctx.handleEvents(SetHedge(coin, position))
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

  /**
    * Internal state that is used for bookkeeping by the Var type classes. This will be set
    * directly by the TradingSession initialization code.
    */
  var buffer: Option[VarBuffer] = None
  implicit def internalStrategyState = buffer.get
}
