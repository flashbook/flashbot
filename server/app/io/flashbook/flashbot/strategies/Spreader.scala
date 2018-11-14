package io.flashbook.flashbot.strategies

import io.circe.Json
import io.circe.generic.auto._
import io.flashbook.flashbot.core.DataSource.DataSourceConfig
import io.flashbook.flashbot.core._
import io.flashbook.flashbot.core.AggBook._
import io.flashbook.flashbot.engine.TradingSession

class Spreader extends Strategy {
  def title = "Spreader"

  case class Props(exchange: String, product: String)
  var params: Option[Props] = None

  override def initialize(paramsJson: Json,
                 dataSourceConfig: Map[String, DataSourceConfig],
                 initialBalances: Map[Account, Double]) = {
    params = Some(paramsJson.as[Props].right.get)
    s"${params.get.exchange}/${params.get.product}/book_10" :: Nil
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession) = data match {
    case md: AggBookMD =>
      println(md.micros, md.data.spread)
//      val spreadBook = "spread_book".get[AggBook]
  }
}
