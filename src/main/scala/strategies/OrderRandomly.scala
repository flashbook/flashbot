package strategies

import core.{BarSize, MarketData, Pair, Strategy, TradingSession}
import io.circe.Json
import io.circe.generic.auto._

import scala.util.Random

class OrderRandomly extends Strategy {

  case class Params(exchange: String, market: String)

  override def title: String = "Random Orders Strategy"

  var _params: Option[Params] = None
  val rand = new Random

  override def initialize(params: Json): List[String] = {
    _params = Some(params.as[Params].right.get)
    List(_params.get.exchange + "/" + _params.get.market + "/trades")
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = {
//    if (rand.nextInt(10) == 0) {
      orderTargetRatio(
        _params.get.exchange,
        _params.get.market,
        if (rand.nextBoolean) 1 else -1)
//    }
  }
}
