package strategies

import core.{BarSize, MarketData, Pair, Strategy, TradingSession}
import io.circe.Json

import scala.util.Random

class OrderRandomly extends Strategy {

  case class Params(exchange: String, market: Pair)

  override def title: String = "Random Orders Strategy"

  var _params: Option[Params] = None
  val rand = new Random

  override def initialize(params: Json)(implicit ctx: TradingSession): List[String] = {
    _params = Some(params.as[Params].right.get)
    List(_params.get.exchange)
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = {
    if (rand.nextInt(100) == 0) {
      orderTargetRatio(rand.nextDouble * 2 - 1)
    }
  }
}
