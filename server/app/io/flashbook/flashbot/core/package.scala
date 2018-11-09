package io.flashbook.flashbot

import io.flashbook.flashbot.core.MarketData.GenMD
import io.flashbook.flashbot.core.Order.{Buy, Sell, Side}
import io.flashbook.flashbot.core.Utils.parseProductId
import io.circe.Json
import io.circe.generic.auto._

package object core {

  case class TimeRange(from: Long, to: Long = Long.MaxValue)

  sealed trait PairRole
  case object Base extends PairRole
  case object Quote extends PairRole

  case class Pair(base: String, quote: String) {
    override def toString: String = s"${base}_$quote"
    def toSeq: Seq[String] = List(base, quote)
  }

  object Pair {
    def apply(str: String): Pair = Utils.parseProductId(str)
  }

  case class Trade(id: String, micros: Long, price: Double, size: Double, side: Side) extends Timestamped

  case class TradeMD(source: String, topic: String, data: Trade)
    extends GenMD[Trade] with Priced {

    val dataType: String = "trades"
    def product: Pair = parseProductId(topic)
    override def micros: Long = data.micros

    override def exchange: String = source

    override def price: Double = data.price
  }

  case class Quote(bidPrice: Double,
                   bidAmount: Double,
                   askPrice: Double,
                   askAmount: Double) {
    def reverse: Quote = Quote(
      bidPrice = askPrice,
      bidAmount = askAmount,
      askPrice = bidPrice,
      askAmount = bidAmount
    )
  }

  case class Ticker(micros: Long,
                    bestBidPrice: Double,
                    bestBidQuantity: Double,
                    bestAskPrice: Double,
                    bestAskQuantity: Double,
                    lastTradePrice: Double,
                    lastTradeId: Long) extends Timestamped

  case class TickerMD(source: String, topic: String, data: Ticker)
    extends GenMD[Ticker] with Priced {

    override def micros: Long = data.micros
    override def dataType: String = "tickers"
    override def exchange: String = source
    override def product: Pair = parseProductId(topic)
    override def price: Double = data.lastTradePrice
  }

  case class Candle(micros: Long,
                    open: Double,
                    high: Double,
                    low: Double,
                    close: Double,
                    volume: Option[Double] = None) extends Timestamped {
    def add(value: Double, newVolume: Option[Double] = None): Candle = copy(
      high = math.max(high, value),
      low = math.min(low, value),
      close = value,
      volume = newVolume.orElse(volume)
    )
  }

  case class CandleMD(source: String, topic: String, data: Candle)
    extends GenMD[Candle] with Priced {

    override def micros: Long = data.micros
    override def dataType: String = "candles"
    override def exchange: String = source
    override def product: Pair = parseProductId(topic)
    override def price: Double = data.close
  }

  case class CurrencyConfig(name: Option[String],
                            alias: Option[String])


  case class BotConfig(strategy: String,
                       mode: String,
                       params: Json,
                       initial_balances: Map[String, Double])

  trait Timestamped {
    def micros: Long
  }
  object Timestamped {
    val ordering: Ordering[Timestamped] = Ordering.by(_.micros)
  }

  trait Priced {
    def exchange: String
    def product: Pair
    def price: Double
  }

  case class PricePoint(price: Double, micros: Long) extends Timestamped
  case class BalancePoint(balance: Double, micros: Long) extends Timestamped

  sealed trait QuoteSide
  case object Bid extends QuoteSide
  case object Ask extends QuoteSide

  case class Account(exchange: String, currency: String)

  case class Market(exchange: String, product: Pair)

  case class Tick(exchange: String)

  sealed trait StrategyEvent
  case class StrategyOrderEvent(targetId: TargetId, event: OrderEvent) extends StrategyEvent

  sealed trait Size
  sealed trait FixedSize extends Size {
    // Size can't be zero
    def size: Double
    def side: Side = size match {
      case s if s < 0 => Sell
      case s if s > 0 => Buy
    }
    def amount: Option[Double]
    def funds: Option[Double]
  }

  case class Amount(size: Double) extends FixedSize {
    override def amount: Option[Double] = Some(size.abs)
    override def funds: Option[Double] = None
  }
  case class Funds(size: Double) extends FixedSize {
    override def amount: Option[Double] = None
    override def funds: Option[Double] = Some(size.abs)
  }

  case class Ratio(ratio: Double, scope: Scope) extends Size


  sealed trait Scope
  case object Portfolio extends Scope
  case object PairScope extends Scope
  case class Basket(coins: Set[String]) extends Scope

  final case class TargetId(pair: Pair, key: String)
}
