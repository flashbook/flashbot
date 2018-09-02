package strategies

import core.DataSource.DataSourceConfig
import core._
import core.Utils.parseProductId
import io.circe.Json
import io.circe.generic.auto._
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

  var ts: Option[TimeSeriesGroup] = None
  var params: Option[Params] = None

  def product = Pair(params.get.market)
  lazy val closePrice = new ClosePriceIndicator(ts.get.get(params.get.exchange, product).get)
  lazy val shortEMA = new EMAIndicator(closePrice, params.get.short)
  lazy val longEMA = new EMAIndicator(closePrice, params.get.long)

  override def initialize(jsonParams: Json,
                          dataSourceConfigs: Map[String, DataSourceConfig],
                          initialBalances: Map[Account, Double]): List[String] = {
    params = Some(jsonParams.as[Params].right.get)
    ts = Some(new TimeSeriesGroup(params.get.barSize))
    List(s"${params.get.exchange}/${params.get.market}/trades")
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = {
    data match {
      case md @ TradeMD(source, topic, Trade(_, _, price, size)) =>
        // Update the time series
        ts.get.record(source, md.product, md.micros, price, Some(size))

        // Set the order targets
        val i = shortEMA.getTimeSeries.getEndIndex
        val diff = shortEMA.getValue(i).doubleValue - longEMA.getValue(i).doubleValue
        if (math.abs(diff) > params.get.dmz) {
          orderTargetRatio(params.get.exchange, product.toString, if (diff > 0) 1 else -1)
        }

        // Send indicators to report
        record("short_ema", shortEMA.getValue(i).doubleValue(), data.micros)
        record("long_ema", longEMA.getValue(i).doubleValue(), data.micros)
    }
  }
}

