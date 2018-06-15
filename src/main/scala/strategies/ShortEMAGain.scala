package strategies

import core.DataSource.DataSourceConfig
import core._
import core.Utils.parseProductId
import io.circe.Json
import io.circe.generic.auto._
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.helpers.{ClosePriceIndicator, GainIndicator}

class ShortEMAGain extends Strategy {

  override def title: String = "Short EMA Gain Reversion"

  case class Params(exchange: String,
                    market: String,
                    bar_size: String,
                    short: Int,
                    long: Int,
                    stop: Double,
                    take: Double)

  val ts = new TimeSeriesGroup
  var params: Option[Params] = None

  def product: Pair = parseProductId(params.get.market)
  lazy val closePrice = new ClosePriceIndicator(ts.get(params.get.exchange, product).get)
  lazy val shortEMA = new EMAIndicator(closePrice, params.get.short)
  lazy val longEMA = new EMAIndicator(closePrice, params.get.long)
  lazy val shortEmaGain = new GainIndicator(shortEMA)

  // TODO: Your target position should be provided by the framework as part of ctx
  var entry: Option[Double] = None

  override def initialize(jsonParams: Json,
                          dataSourceConfig: Map[String, DataSourceConfig]): List[String] = {
    params = Some(jsonParams.as[Params].right.get)
    ts.setPeriod(params.get.bar_size)
    List(s"${params.get.exchange}/${params.get.market}/trades")
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = data match {
    case md @ TradeMD(source, topic, Trade(_, _, price, size)) =>
      // Update the time series
      ts.record(source, md.product, md.micros, price, Some(size))

      val i = shortEmaGain.getTimeSeries.getEndIndex
      val shortGain = shortEmaGain.getValue(i).doubleValue
      val long = longEMA.getValue(i).doubleValue
      val short = shortEMA.getValue(i).doubleValue

      if (entry.isDefined) {
        // Currently in position. Check for stop loss and take profit to see if we want to exit.
        val returns = 1 - price / entry.get
        val shouldTake = returns > 0 && returns > params.get.take
        val shouldStop = returns < 0 && -returns > params.get.stop

        if (shouldTake || shouldStop) {
          orderTargetRatio(params.get.exchange, product.toString, -1)
          entry = None
        }

      } else {
        // No position, we're interested in possibly entering one. Criteria are that:
        // 1. Short EMA gain must be positive
        // 2. Short EMA value is lower than long EMA
        if (shortGain > 0 && short < long) {
          orderTargetRatio(params.get.exchange, product.toString, 1)
          entry = Some(price)
        }
      }

      // Send indicators to report
      metric("short_ema", short, data.micros)
      metric("long_ema", long, data.micros)
  }
}
