package strategies

import java.time.{Instant, ZonedDateTime}

import core.Strategy.PureStrategy
import core.{BarSize, MarketData, Pair, Trade, TradingSession}
import io.circe.Json
import org.ta4j.core.{BaseTimeSeries, TimeSeries}

/**
  * This is an example of how to build a trading strategy based on common technical indicators.
  */
class DualMovingAverageCrossover extends PureStrategy {
  import io.circe.generic.auto._

  case class Params(exchange: String,
                    market: Pair,
                    barSize: BarSize,
                    shortTimePeriod: Int,
                    longTimePeriod: Int)

  var _params: Option[Params] = None
  def params: Params = _params.get

  override val name = "Dual Moving Average Crossover"

  val prices: TimeSeries =
    new BaseTimeSeries.SeriesBuilder().withName(s"${params.market}_prices").build()

  override def initialize(jsonParams: Json)(implicit ctx: TradingSession): List[String] = {
    _params = Some(jsonParams.as[Params].right.get)
    List(s"${params.exchange}/${params.market}/trades")
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = {
    data match {
      case trade: Trade =>
    }
  }
}
