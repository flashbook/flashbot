package strategies

import java.time.{Instant, ZonedDateTime}

import core._
import io.circe.Json
import org.ta4j.core.{BaseTimeSeries, TimeSeries}
import io.circe.generic.auto._
import io.circe.syntax._

/**
  * This is an example of how to build a trading strategy based on common technical indicators
  * from the Ta4j library.
  */
class DualMovingAverageCrossover extends Strategy {

  override val title = "Dual Moving Average Crossover"

  case class Params(exchange: String,
                    market: String,
                    barSize: String,
                    shortTimePeriod: Int,
                    longTimePeriod: Int)

  var _params: Option[Params] = None
  def params: Params = _params.get

  val prices: TimeSeries =
    new BaseTimeSeries.SeriesBuilder().withName(s"${params.market}_prices").build()

  override def initialize(jsonParams: Json): List[String] = {
    _params = Some(jsonParams.as[Params].right.get)
    List(s"${params.exchange}/${params.market}/trades")
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = {
    data match {
      case TradeMD(source, topic, Trade(id, time, price, size)) =>
    }
  }
}
