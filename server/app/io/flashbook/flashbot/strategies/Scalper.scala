package io.flashbook.flashbot.strategies

import io.flashbook.flashbot.core.DataSource.DataSourceConfig
import io.flashbook.flashbot.core._
import io.flashbook.flashbot.util
import io.circe.Json
import io.circe.generic.auto._
import io.flashbook.flashbot.core.Instrument.CurrencyPair
import io.flashbook.flashbot.engine.TradingSession
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.helpers.{ClosePriceIndicator, GainIndicator}

import scala.concurrent.Future

class Scalper extends Strategy {

  override def title: String = "Scalper"

  case class Params(exchange: String,
                    market: String,
                    bar_size: String,
                    limit: String,
                    short: Int,
                    long: Int,
                    stop: Double,
                    take: Double)

  var ts: Option[TimeSeriesGroup] = None
  var params: Option[Params] = None

  def product = CurrencyPair(params.get.market)
  lazy val closePrice = new ClosePriceIndicator(ts.get.get(params.get.exchange, product).get)
  lazy val shortEMA = new EMAIndicator(closePrice, params.get.short)
  lazy val longEMA = new EMAIndicator(closePrice, params.get.long)
  lazy val shortEmaGain = new GainIndicator(shortEMA)

  // TODO: Your target position should be provided by the framework as part of ctx
  var entry: Option[(Long, Double)] = None

  override def initialize(jsonParams: Json,
                          portfolio: Portfolio,
                          loader: SessionLoader) = Future.successful {
    params = Some(jsonParams.as[Params].right.get)
    ts = Some(new TimeSeriesGroup(params.get.bar_size))
    s"${params.get.exchange}/${params.get.market}/trades" :: Nil
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = data match {
    case md @ TradeMD(source, topic, Trade(_, _, price, size, _)) =>
      // Update the time series
      ts.get.record(source, md.product, md.micros, price, Some(size))

      val i = shortEmaGain.getTimeSeries.getEndIndex
      val shortGain = shortEmaGain.getValue(i).doubleValue
      val long = longEMA.getValue(i).doubleValue
      val short = shortEMA.getValue(i).doubleValue

      if (entry.isDefined) {
        // Currently in position. Check for stop loss and take profit to see if we want to exit.
        val returns = 1 - price / entry.get._2
        val shouldTake = returns > 0 && returns > params.get.take
        val shouldStop = returns < 0 && -returns > params.get.stop
        val timeLimitReached = (md.micros - entry.get._1) >
          util.time.parseDuration(params.get.limit).toMicros

        if (shouldTake || shouldStop || timeLimitReached) {
          orderTargetRatio(params.get.exchange, product.toString, -1)
          entry = None
        }

      } else {
        // No position, we're interested in possibly entering one. Criteria are that:
        // 1. Short EMA gain must be positive
        // 2. Short EMA value is lower than long EMA
        if (shortGain > 0 && short < long) {
          orderTargetRatio(params.get.exchange, product.toString, 1)
          entry = Some(md.micros, price)
        }
      }

      // Send indicators to report
      record("short_ema", short, data.micros)
      record("long_ema", long, data.micros)
  }
}
