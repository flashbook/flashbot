package io.flashbook.flashbot.strategies

import com.github.andyglow.jsonschema.AsCirce._
import io.circe.generic.semiauto._

import io.flashbook.flashbot.core._
import io.flashbook.flashbot.engine.{SessionLoader, Strategy, TradingSession}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Spreader extends Strategy {

  def title = "Spreader"

  case class Params(exchange: String, product: String)

  def paramsDecoder = deriveDecoder[Params]

  override def info(loader: SessionLoader) = Future {
    Some(StrategyInfo(json.Json.schema[Params].asCirce()))
  }

  override def initialize(portfolio: Portfolio, loader: SessionLoader) = {
    val src = params.exchange
    val topic = params.product
    loader.index.map(_.filter(s"$src/$topic/ladder").paths)
  }

  override def handleData(data: MarketData[_])(implicit ctx: TradingSession) = data match {
    case md: MarketData[Ladder] =>
      println(md.micros, md.data.spread)
  }
}
