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
                    dmz: Double,
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
        val diff = shortEMA.getValue(i).doubleValue - longEMA.getValue(i).doubleValue
        if (math.abs(diff) > params.get.dmz) {
          orderTargetRatio(params.get.exchange, product.toString, if (diff > 0) 1 else -1)
        }

        // Send indicators to report
        metric("short_ema", shortEMA.getValue(i).doubleValue(), data.micros)
        metric("long_ema", longEMA.getValue(i).doubleValue(), data.micros)
    }
  }
}

