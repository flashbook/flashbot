package io.flashbook.flashbot.core


import java.util.UUID

import io.circe._
import io.flashbook.flashbot.core.DataSource.{Address, DataSourceConfig}
import io.flashbook.flashbot.core.Instrument.CurrencyPair
import io.flashbook.flashbot.engine.TradingSession
import io.flashbook.flashbot.engine.TradingSession.{OrderTarget, SessionReportEvent}
import io.flashbook.flashbot.report.ReportEvent._
import io.flashbook.flashbot.core.Convert._

import scala.concurrent.Future

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
  def info(loader: SessionLoader): Future[Option[StrategyInfo]] = Future.successful(None)

  /**
    * During initialization, strategies declare what data sources they need by name, all of which
    * must be registered in the system or an error is thrown. If all is well, the data sources are
    * loaded and are all queried for a certain time period and results are merged and streamed into
    * the `handleData` method. Each stream should complete when there is no more data, which auto
    * shuts down the strategy when all data streams complete.
    */
  def initialize(params: Json, portfolio: Portfolio, loader: SessionLoader): Future[Seq[String]]

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

//  def orderTargetRatio(exchangeName: String,
//                       product: String,
//                       ratio: Double,
//                       price: Option[Double] = None,
//                       key: String = DEFAULT,
//                       postOnly: Boolean = false)
//                      (implicit ctx: TradingSession): Unit = {
//    val market = Market(exchangeName, product)
//    val instrument = ctx.instruments(market)
//    val targetNotionalPosition = PositionManager.percentGroup(ctx.getPortfolio,
//      Seq(market),
//      Map(instrument.security.get -> ratio),
//      ctx.getPrices,
//      equityDenomination = instrument.settledIn
//    )(market)
//
//    ctx.send(OrderTarget(
//      exchangeName,
//      TargetId(instrument, key),
//      Quantity(???),
//      price,
//      postOnly
//    ))
//  }

  /**
    * Usage:
    *
    * val up = order("up_limit", size = "10 usd")
    * val downOrder = order(size = "20 usd", market = "btc/usd")
    *
    * val btcPositionOverall = position("my_pos", "btc")
    * val btcPositionBitmex = position("my_pos", "btc", "bitmex")
    *
    * val ethPosition = btcPosition / 2
    *
    * if (something)
    *   btcPosition("usd") = ethPosition("usd")
    *
    * maximize(ethPosition.as("ltc") - btcPosition.as("ltc"))
    *
    */

  @Deprecated
  def orderTargetRatio(exchange: String, product: String, ratio: Double)
                      (implicit ctx: TradingSession): String = {
    val pair = CurrencyPair(product)
    val baseBalance = FixedSize(ctx.getPortfolio.assets(Account(exchange, pair.base)), pair.base)
    val quoteBalance = FixedSize(ctx.getPortfolio.assets(Account(exchange, pair.quote)), pair.quote)

    val notionalBase = baseBalance.as(pair.quote)(ctx.getPrices, ctx.instruments).get
    val totalNotional = quoteBalance.amount + notionalBase.amount

    val target = OrderTarget(
      Market(exchange, product),
      DEFAULT,
      FixedSize(totalNotional * ratio, pair.quote),
      None
    )
    ctx.send(target)
    target.id
  }

  def limitOrder(market: Market,
                 size: FixedSize,
                 price: Double,
                 key: String = DEFAULT,
                 postOnly: Boolean = false)
                (implicit ctx: TradingSession): String = {
    val target = OrderTarget(
      market,
      key,
      size,
      Some(price),
      once = Some(false),
      postOnly = Some(postOnly)
    )
    ctx.send(target)
    target.id
  }

  def limitOrderOnce(market: Market,
                     size: FixedSize,
                     price: Double,
                     postOnly: Boolean = false)
                    (implicit ctx: TradingSession): String = {
    val target = OrderTarget(
      market,
      UUID.randomUUID().toString,
      size,
      Some(price),
      once = Some(true),
      postOnly = Some(postOnly)
    )
    ctx.send(target)
    target.id
  }

  def marketOrder(market: Market, size: FixedSize)
                 (implicit ctx: TradingSession): String = {
    val target = OrderTarget(
      market,
      UUID.randomUUID().toString,
      size,
      None
    )
    ctx.send(target)
    target.id
  }

  def record(name: String, value: Double, micros: Long)
            (implicit ctx: TradingSession): Unit = {
    ctx.send(SessionReportEvent(TimeSeriesEvent(name, value, micros)))
  }

  def record(name: String, candle: Candle)
            (implicit ctx: TradingSession): Unit = {
    ctx.send(SessionReportEvent(TimeSeriesCandle(name, candle)))
  }

  def resolveAddress(address: Address): Option[Iterator[MarketData]] = None

  /**
    * Internal state that is used for bookkeeping by the Var type classes. This will be set
    * directly by the TradingSession initialization code.
    */
  var buffer: Option[VarBuffer] = None
  implicit def internalStrategyState = buffer.get
}
