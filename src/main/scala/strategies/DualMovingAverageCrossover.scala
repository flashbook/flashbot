package strategies

import java.time.{Duration, Instant, ZoneOffset, ZonedDateTime}

import core._
import core.Utils.{parseDuration, parseProductId}
import io.circe.Json
import org.ta4j.core.{BaseTimeSeries, TimeSeries}
import io.circe.generic.auto._
import io.circe.syntax._
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator

/**
  * This is an example of how to build a trading strategy based on common technical indicators
  * from the Ta4j library.
  */
class DualMovingAverageCrossover extends Strategy {

  override val title = "Dual Moving Average Crossover"

  case class Params(exchange: String,
                    market: String,
                    barSize: String,
                    short: Int,
                    long: Int)

  val ts = new TimeSeriesGroup
  var params: Option[Params] = None

  def product: Pair = parseProductId(params.get.market)
  lazy val closePrice = new ClosePriceIndicator(ts.get(params.get.exchange, product).get)
  lazy val shortEMA = new EMAIndicator(closePrice, params.get.short)
  lazy val longEMA = new EMAIndicator(closePrice, params.get.long)

  override def initialize(jsonParams: Json): List[String] = {
    params = Some(jsonParams.as[Params].right.get)
    ts.setPeriod(params.get.barSize)
    List(s"${params.get.exchange}/${params.get.market}/trades")
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = {
    data match {
      case md @ TradeMD(source, topic, Trade(_, _, price, size)) =>
        // Update the time series
        ts.record(source, md.product, md.micros, price, Some(size))

        // Set the order targets
        val i = shortEMA.getTimeSeries.getEndIndex
        val target = shortEMA.getValue(i) compareTo longEMA.getValue(i)
        orderTargetRatio(params.get.exchange, product.toString, target)

        // Send indicators to report
        metric("short_ema", shortEMA.getValue(i).doubleValue(), data.micros)
        metric("long_ema", longEMA.getValue(i).doubleValue(), data.micros)
    }
  }
}

class TimeSeriesGroup {

  var allSeries: Map[String, TimeSeries] = Map.empty

  var period: Option[Duration] = None

  def setPeriod(durationStr: String): Unit = {
    period = Some(Duration.ofNanos(parseDuration(durationStr).toNanos))
  }

  def record(exchange: String,
             product: Pair,
             micros: Long,
             price: Double,
             amount: Option[Double] = None): Unit = {
    val key = _key(exchange, product)
    val series =
      if (allSeries.isDefinedAt(key)) allSeries(key)
      else new BaseTimeSeries.SeriesBuilder().withName(key).build()

    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(micros / 1000), ZoneOffset.UTC)

    // Until the last bar exists and accepts the current time, create a new bar.
    while (series.getBarCount == 0 || !series.getLastBar.inPeriod(zdt)) {
      val startingTime = if (series.getBarCount == 0) zdt else series.getLastBar.getEndTime
      series.addBar(period.get, startingTime.plus(period.get))
    }

    // Now we have the correct last bar, add the price or trade
    if (amount.isDefined) {
      series.addTrade(amount.get, price)
    } else {
      series.addPrice(price)
    }

    allSeries = allSeries + (key -> series)
  }

  def get(exchange: String, product: Pair): Option[TimeSeries] =
    allSeries.get(_key(exchange, product))

  def _key(exchange: String, product: Pair): String = s"$exchange.$product"
}
