package core

import java.time.{Duration, Instant, ZoneOffset, ZonedDateTime}

import core.Utils.parseDuration
import org.ta4j.core.{BaseTimeSeries, TimeSeries}

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
