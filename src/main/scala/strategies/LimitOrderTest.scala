package strategies

import core.AggBook.{AggBook, AggBookMD}
import core.DataSource.DataSourceConfig
import core._
import io.circe.Json
import io.circe.generic.auto._

import scala.collection.immutable.TreeMap

/**
  * The LimitOrderTest strategy is a POC of the Flashbot limit order functionality. It simply
  * maintains a limit order 5 price levels away from the best bid/ask on each side of the book.
  * Fills should be rare.
  */
class LimitOrderTest extends Strategy {

  case class Params(exchange: String, product: String, order_size: Double)
  var params: Option[Params] = None

  override def title: String = "Limit Order Test"

  override def initialize(paramsJson: Json,
                          dataSourceConfig: Map[String, DataSourceConfig],
                          initialBalances: Map[Account, Double]): List[String] = {
    params = Some(paramsJson.as[Params].right.get)
    s"${params.get.exchange}/${params.get.product}/book_10" :: Nil
  }

  override def handleEvent(event: StrategyEvent)(implicit ctx: TradingSession): Unit = {
    println(event)
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = data match {
    case md: AggBookMD =>
      println(md)

      val asks = md.data.asks.asInstanceOf[TreeMap[Double, Double]]
      val bids = md.data.bids.asInstanceOf[TreeMap[Double, Double]]

      order(params.get.exchange, params.get.product, params.get.order_size,
        price = Some(bids.drop(4).head._1), key = "bid_quote")

      order(params.get.exchange, params.get.product, -params.get.order_size,
        price = Some(asks.drop(4).head._1), key = "ask_quote")
  }
}
