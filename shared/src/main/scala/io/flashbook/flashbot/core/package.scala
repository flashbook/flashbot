package io.flashbook.flashbot

import java.time.Instant
import scala.concurrent.duration._

import io.flashbook.flashbot.core.MarketData.GenMD
import io.flashbook.flashbot.core.Order.{Buy, Sell, Side}
import io.flashbook.flashbot.util.time.parseDuration
import io.flashbook.flashbot.util.parseProductId

import scala.concurrent.duration.{Duration, FiniteDuration}

import io.circe._
import io.circe.generic.auto._

package object core {

  case class TimeRange(from: Long, to: Long = Long.MaxValue)
  object TimeRange {
    def build(now: Instant, from: String, to: String): TimeRange = {
      val fromT = parseTime(now, from)
      val toT = parseTime(now, to)
      (fromT, toT) match {
        case (Right(inst), Left(dur)) => TimeRange(
          inst.toEpochMilli * 1000,
          inst.plusMillis(dur.toMillis).toEpochMilli * 1000)
        case (Left(dur), Right(inst)) => TimeRange(
          inst.minusMillis(dur.toMillis).toEpochMilli * 1000,
          inst.toEpochMilli * 1000)
      }
    }
  }

  def parseTime(now: Instant, str: String): Either[Duration, Instant] = {
    if (str == "now") {
      Right(now)
    } else {
      Left(parseDuration(str))
    }
  }

  sealed trait PairRole
  case object Base extends PairRole
  case object Quote extends PairRole

//  case class Pair(base: String, quote: String) {
//    override def toString: String = s"${base}_$quote"
//    def toSeq: Seq[String] = List(base, quote)
//  }

//  object Pair {
//    def apply(str: String): Pair = parseProductId(str)
//  }

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

  case class Account(exchange: String, security: String)

  case class Market(exchange: String, instrument: Instrument)

  case class Tick(events: Seq[Any] = Seq.empty, exchange: Option[String] = None)

  sealed trait StrategyEvent
  case class StrategyOrderEvent(targetId: TargetId, event: OrderEvent) extends StrategyEvent

  sealed trait StrategyCommand

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

  case class Ratio(ratio: Double,
                   extraBaseAssets: Set[String] = Set.empty,
                   basePegs: Boolean = false) extends Size

  final case class TargetId(pair: Pair, key: String)
}
