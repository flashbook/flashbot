package strategies

import java.text.SimpleDateFormat

import core.AggBook.{AggBook, AggBookMD}
import core.DataSource.DataSourceConfig
import core.Order.Buy
import core._
import io.circe.Json
import io.circe.generic.auto._
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.volume.VWAPIndicator
import util.TablePrinter

import scala.collection.immutable.{Queue, TreeMap}

/**
  * The LimitOrderTest strategy is a POC of the Flashbot limit order functionality. It simply
  * maintains a limit order 5 price levels away from the best bid/ask on each side of the book.
  * Fills should be rare.
  */
class LimitOrderTest extends Strategy {
  override def title: String = "Limit Order Test"
  case class Params(exchange: String, product: String, order_size: Double)
  var params: Option[Params] = None

  private lazy val tsg = new TimeSeriesGroup("1m")
  private def ts = tsg.get(params.get.exchange, params.get.product)
  private lazy val closePrice = new ClosePriceIndicator(ts.get)
  private lazy val vwap = new VWAPIndicator(ts.get, 10)

  var book: Option[AggBook] = None
  var lastTrades = Queue.empty[TradeMD]

  override def initialize(paramsJson: Json,
                          dataSourceConfig: Map[String, DataSourceConfig],
                          initialBalances: Map[Account, Double]): List[String] = {
    params = Some(paramsJson.as[Params].right.get)
    s"${params.get.exchange}/${params.get.product}/book_10" ::
      s"${params.get.exchange}/${params.get.product}/trades" :: Nil
  }

  var priceToOrderKey = Map.empty[Double, String]
  override def handleEvent(event: StrategyEvent)(implicit ctx: TradingSession): Unit = {
    event match {
      case StrategyOrderEvent(targetId, ev) => ev match {
        case e: OrderOpen =>
//          println(s"Order open: ${targetId.key} at ${e.price}")
          priceToOrderKey += (e.price -> targetId.key)
        case e: OrderDone =>
//          println(s"Order cancel: ${targetId.key} at ${e.price}")
          if (e.reason == Filled) {
            println(s"Order filled: ${targetId.key} at ${e.price.get}")
          }
          priceToOrderKey -= e.price.get
        case _ =>
      }
    }

    render()
  }

  override def handleData(data: MarketData)(implicit ctx: TradingSession): Unit = data match {
    case md: AggBookMD =>
      val asks = md.data.asks.asInstanceOf[TreeMap[Double, Double]]
      val bids = md.data.bids.asInstanceOf[TreeMap[Double, Double]]

      book = Some(md.data)

      // Place orders
      order(params.get.exchange, params.get.product, params.get.order_size,
        price = Some(bids.drop(0).head._1), key = "bid_quote")
      order(params.get.exchange, params.get.product, -params.get.order_size,
        price = Some(asks.drop(0).head._1), key = "ask_quote")

      render()

    case md: TradeMD =>

      lastTrades = lastTrades.enqueue(md).takeRight(10)

      try {
        tsg.record(md.exchange, md.product, md.micros, md.price, Some(md.data.size))
      } catch {
        case err: Throwable =>
          err.printStackTrace()
      }
  }

  val timeFormatter = new SimpleDateFormat("hh:mm:ss")
  def render(): Unit = {
    val asks = book.get.asks.asInstanceOf[TreeMap[Double, Double]]
    val bids = book.get.bids.asInstanceOf[TreeMap[Double, Double]]

    // Print the ladder
    val askRows = asks.toSeq.reverse.map { case (price, quantity) =>
      priceToOrderKey.getOrElse(price, "") :: "" :: price.toString :: quantity.toString :: Nil
    }
    val bidRows = bids.toSeq.map { case (price, quantity) =>
      priceToOrderKey.getOrElse(price, "") :: quantity.toString :: price.toString :: "" :: Nil
    }

    println("\n" * 100)
    println("Order Book:")
    println(TablePrinter.format(("Order ID" :: "Bid" :: "Price" :: "Ask" :: Nil) +:
      (askRows ++ bidRows)))

    // Print the last trades
    println("\n")
    println("Last Trades:")
    val tradeRows = lastTrades.reverse.map {
      case TradeMD(_, _, Trade(id, micros, price, size, side)) =>
        (if (side == Buy) "▲" else "▼") :: price.toString :: size.toString ::
          timeFormatter.format(new java.util.Date(micros / 1000)) :: Nil
    }
    println(TablePrinter.format(("" :: "Price" :: "Size" :: "Time" :: Nil) +: tradeRows))
  }
}
