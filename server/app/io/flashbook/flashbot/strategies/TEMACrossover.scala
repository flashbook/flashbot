package io.flashbook.flashbot.strategies

import io.flashbook.flashbot.core._
import io.circe.Json
import io.circe.generic.auto._
import io.flashbook.flashbot.core.Instrument.CurrencyPair
import io.flashbook.flashbot.engine.TradingSession
import org.ta4j.core.indicators.TripleEMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator

import scala.concurrent.Future

/**
  * Similar to the DualMovingAverageCrossover strategy, except it uses Triple EMA indicators
  * to reduce lag.
  */
class TEMACrossover extends Strategy {

  override def title: String = "Triple EMA Crossover"

  case class Params(exchange: String,
                    market: String,
                    bar_size: String,
                    dmz: Double,
                    short: Int,
                    long: Int)

  var ts: Option[TimeSeriesGroup] = None
  var params: Option[Params] = None
  def product = CurrencyPair(params.get.market)

  // Indicators
  lazy val close = new ClosePriceIndicator(ts.get.get(params.get.exchange, product).get)
  lazy val shortTEMA = new TripleEMAIndicator(close, params.get.short)
  lazy val longTEMA = new TripleEMAIndicator(close, params.get.long)

  override def initialize(paramsJson: Json,
                          portfolio: Portfolio,
                          loader: SessionLoader): Future[Seq[String]] = Future.successful {
    params = Some(paramsJson.as[Params].right.get)
    ts = Some(new TimeSeriesGroup(params.get.bar_size))
    s"${params.get.exchange}/${params.get.market}/trades" :: Nil
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = data match {
    case md @ TradeMD(source, topic, Trade(_, _, price, size, _)) =>

      println(md)

      // Update the time series
      ts.get.record(source, md.product, md.micros, price, Some(size))

      // Set order targets
      val i = shortTEMA.getTimeSeries.getEndIndex
      val diff = shortTEMA.getValue(i).doubleValue - longTEMA.getValue(i).doubleValue
      if (math.abs(diff) > params.get.dmz) {
        orderTargetRatio(params.get.exchange, product.toString, if (diff > 0) 1 else -1)
      }

      // Send indicators to report
      record("long_tema", longTEMA.getValue(i).doubleValue(), data.micros)
      record("short_tema", shortTEMA.getValue(i).doubleValue(), data.micros)
  }
}
