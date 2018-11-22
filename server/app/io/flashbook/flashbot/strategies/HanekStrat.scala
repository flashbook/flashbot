package io.flashbook.flashbot.strategies

import java.time.{Instant, LocalDateTime, ZoneOffset}

import io.flashbook.flashbot.core.DataSource.{Address, DataSourceConfig}
import io.flashbook.flashbot.core.Order.{Buy, Sell}
import io.flashbook.flashbot.core._
import io.flashbook.flashbot.util
import io.circe.Json
import io.circe.generic.auto._
import io.flashbook.flashbot.core.Candle.CandleMD
import io.flashbook.flashbot.core.Instrument.CurrencyPair
import io.flashbook.flashbot.engine.TradingSession
import org.ta4j.core.indicators.{RSIIndicator, SMAIndicator, StochasticOscillatorDIndicator, StochasticOscillatorKIndicator}
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.{SimpleLinearRegressionIndicator, StandardDeviationIndicator}

import scala.concurrent.Future
import scala.io.Source
import scala.util.matching.Regex
import scala.concurrent.duration._

class HanekStrat extends Strategy {
  override def title = "The one and only, Hanek Strategy"

  /**
    * Params
    */
  case class Params(pair: String,
                    rsiThreshold: Int,
                    stochThreshold: Int,
                    meanLookback: Int,
                    numStdDevs: Double)
  private var params: Option[Params] = None
  private var hedges: Map[String, Long] = Map.empty

  /**
    * Indicators
    */
  private lazy val tsg = new TimeSeriesGroup("1h")
  private def pair = CurrencyPair(params.get.pair.toLowerCase)
  private def price = tsg.get("bitfinex", pair)
  private lazy val close = new ClosePriceIndicator(price.get)
  private lazy val rsi = new RSIIndicator(close, 10)
  private lazy val rsiSmoothed = new SMAIndicator(rsi, 3)

  private lazy val stochK = new StochasticOscillatorKIndicator(price.get, 14)
  private lazy val stochD = new StochasticOscillatorDIndicator(stochK)

  private lazy val stochDSlope = new SimpleLinearRegressionIndicator(stochD, 2,
    SimpleLinearRegressionIndicator.SimpleLinearRegressionType.slope)
  private lazy val rsiSlope = new SimpleLinearRegressionIndicator(rsiSmoothed, 2,
    SimpleLinearRegressionIndicator.SimpleLinearRegressionType.slope)

  private lazy val rsiMinMean = new ClosePriceIndicator(tsg.get("bitfinex", "rsi_local_min_mean").get)
  private lazy val rsiMaxMean = new ClosePriceIndicator(tsg.get("bitfinex", "rsi_local_max_mean").get)
  private lazy val stochMinMean = new ClosePriceIndicator(tsg.get("bitfinex", "stoch_local_min_mean").get)
  private lazy val stochMaxMean = new ClosePriceIndicator(tsg.get("bitfinex", "stoch_local_max_mean").get)

  private lazy val rsiMinStdDev = new StandardDeviationIndicator(rsiMinMean, params.get.meanLookback)
  private lazy val rsiMaxStdDev = new StandardDeviationIndicator(rsiMaxMean, params.get.meanLookback)
  private lazy val stochMinStdDev = new StandardDeviationIndicator(stochMinMean, params.get.meanLookback)
  private lazy val stochMaxStdDev = new StandardDeviationIndicator(stochMaxMean, params.get.meanLookback)

  private lazy val rsiMean = new SMAIndicator(rsiSmoothed, params.get.meanLookback)
  private lazy val stochMean = new SMAIndicator(stochD, params.get.meanLookback)

  override def initialize(jsonParams: Json,
                          portfolio: Portfolio,
                          loader: SessionLoader) = {
    Future.successful {
      params = Some(jsonParams.as[Params].right.get)
      s"bitfinex/${params.get.pair}/candles_1h" :: Nil
    }
  }

  sealed trait Position
  case object Long extends Position
  case object Short extends Position
  case object Neutral extends Position

  var state: Position = Short
  var lastRsiSlope: Double = Double.NaN
  var lastStochSlope: Double = Double.NaN

  var lastRsiMinOutlierTime = Instant.EPOCH
  var lastRsiMaxOutlierTime = Instant.EPOCH
  var lastStochMinOutlierTime = Instant.EPOCH
  var lastStochMaxOutlierTime = Instant.EPOCH

  override def handleEvent(event: StrategyEvent)(implicit ctx: TradingSession) = event match {
    case StrategyOrderEvent(targetId, e: OrderEvent) =>
      e match {
        case OrderDone(_, _, Buy, Filled, _, _) =>
          println("Bought", state, ctx.getPortfolio)
        case OrderDone(_, _, Sell, Filled, _, _) =>
          println("Sold", state, ctx.getPortfolio)
        case _ =>
      }
  }

  var locals = Map.empty[String, Seq[(Instant, Double)]]
  def recordLocal(tsName: String, micros: Long, value: Double, now: Instant): Unit = {
    locals += (tsName ->
      (locals.getOrElse(tsName, Seq.empty) :+ (Instant.ofEpochMilli(micros / 1000), value))
      .filter {
        case (time, _) => time.isAfter(now.minusMillis(params.get.meanLookback.hours.toMillis))
      })
    val mean = locals(tsName).map(_._2).sum / locals(tsName).size
    tsg.record("bitfinex", tsName, now.toEpochMilli * 1000, mean)
  }

  var count: Long = 0

  override def handleData(data: MarketData)(implicit ctx: TradingSession) = data match {
    case md @ CandleMD(source, topic, candle) =>
      val now = Instant.ofEpochMilli(md.micros / 1000)
//      hedges.foreach { case (k, v) => setHedge(k, v)}

      tsg.record("bitfinex", md.product, candle)
      record("price", candle)
      val rsiVal = rsi.getValue(rsiSmoothed.getTimeSeries.getEndIndex).doubleValue
      record("rsi", rsiVal, md.micros)

      val stochVal = stochD.getValue(stochD.getTimeSeries.getEndIndex).doubleValue

      if (state == Neutral) {
        record("usd_balance", ctx.getPortfolio.balance(Account("bitfinex", "usd")), md.micros)
      }

      val rsiSlopeVal = rsiSlope.getValue(rsiSlope.getTimeSeries.getEndIndex).doubleValue
      val stochSlopeVal = stochDSlope.getValue(stochDSlope.getTimeSeries.getEndIndex).doubleValue

      var localRsiMin: Double = Double.NaN
      var localRsiMax: Double = Double.NaN
      var localStochMin: Double = Double.NaN
      var localStochMax: Double = Double.NaN

      val prevLastRsiSlope = lastRsiSlope
      val prevLastStochSlope = lastStochSlope
      lastRsiSlope = rsiSlopeVal
      lastStochSlope = stochSlopeVal

      if (!prevLastRsiSlope.isNaN && !prevLastStochSlope.isNaN) {
        println("rsi slope", prevLastRsiSlope, rsiSlopeVal)
        println("stoch slope", prevLastStochSlope, stochSlopeVal)
        if (prevLastRsiSlope < 0 && rsiSlopeVal >= 0) {
          localRsiMin = rsiVal
          recordLocal("rsi_local_min_mean", md.micros, rsiVal, now)
        }
        if (prevLastRsiSlope > 0 && rsiSlopeVal <= 0) {
          localRsiMax = rsiVal
          recordLocal("rsi_local_max_mean", md.micros, rsiVal, now)
        }

        if (prevLastStochSlope < 0 && stochSlopeVal >= 0) {
          localStochMin = stochVal
          recordLocal("stoch_local_min_mean", md.micros, stochVal, now)
        }
        if (prevLastStochSlope > 0 && stochSlopeVal <= 0) {
          localStochMax = stochVal
          recordLocal("stoch_local_max_mean", md.micros, stochVal, now)
        }

        var rsiMeanVal = Double.NaN
        var stochMeanVal = Double.NaN

        var rsiMinMeanVal = Double.NaN
        var rsiMaxMeanVal = Double.NaN
        var stochMinMeanVal = Double.NaN
        var stochMaxMeanVal = Double.NaN

        var rsiMinStdDevVal = Double.NaN
        var rsiMaxStdDevVal = Double.NaN
        var stochMinStdDevVal = Double.NaN
        var stochMaxStdDevVal = Double.NaN

        if (locals.size < 4) {
          println("returning cuz no locals", locals.keySet)
        } else if (count < 1000) {
          count += 1
          println("returning cuz < 100")
        } else {

          rsiMeanVal = rsiMean.getValue(rsiMean.getTimeSeries.getEndIndex).doubleValue
          stochMeanVal = stochMean.getValue(stochMean.getTimeSeries.getEndIndex).doubleValue

          rsiMinMeanVal = rsiMinMean.getValue(rsiMinMean.getTimeSeries.getEndIndex).doubleValue
          rsiMaxMeanVal = rsiMaxMean.getValue(rsiMaxMean.getTimeSeries.getEndIndex).doubleValue
          stochMinMeanVal = stochMinMean.getValue(stochMinMean.getTimeSeries.getEndIndex).doubleValue
          stochMaxMeanVal = stochMaxMean.getValue(stochMaxMean.getTimeSeries.getEndIndex).doubleValue

          rsiMinStdDevVal = rsiMinStdDev.getValue(rsiMinStdDev.getTimeSeries.getEndIndex).doubleValue
          rsiMaxStdDevVal = rsiMaxStdDev.getValue(rsiMaxStdDev.getTimeSeries.getEndIndex).doubleValue
          stochMinStdDevVal = stochMinStdDev.getValue(stochMinStdDev.getTimeSeries.getEndIndex).doubleValue
          stochMaxStdDevVal = stochMaxStdDev.getValue(stochMaxStdDev.getTimeSeries.getEndIndex).doubleValue

          if (Seq(
            rsiMinMeanVal, rsiMaxMeanVal, stochMinMeanVal, stochMaxMeanVal,
            rsiMinStdDevVal, rsiMaxStdDevVal, stochMinStdDevVal, stochMaxStdDevVal,
            rsiMeanVal, stochMeanVal
          ).forall(!_.isNaN)) {
            println("hi", rsiMinMeanVal, stochMinMeanVal)

            val rsiCeil = rsiMaxMeanVal + rsiMaxStdDevVal * params.get.numStdDevs
            val rsiFloor = rsiMinMeanVal - rsiMinStdDevVal * params.get.numStdDevs
            val stochCeil = stochMaxMeanVal + stochMaxStdDevVal * params.get.numStdDevs
            val stochFloor = stochMinMeanVal - stochMinStdDevVal * params.get.numStdDevs

            if (!localRsiMin.isNaN && localRsiMin <= rsiFloor)
              lastRsiMinOutlierTime = now
            if (!localRsiMax.isNaN && localRsiMax >= rsiCeil)
              lastRsiMaxOutlierTime = now
            if (!localStochMin.isNaN && localStochMin <= stochFloor)
              lastStochMinOutlierTime = now
            if (!localStochMax.isNaN && localStochMax >= stochCeil)
              lastStochMaxOutlierTime = now

            // We should exit if we are long and either the rsi or stoch value is above its average.
            // OR if we are short and either the rsi or stoch is below its average.
            val shouldExit = (state == Long && (rsiVal >= rsiMeanVal || stochVal >= stochMeanVal)) ||
              (state == Short && (rsiVal <= rsiMeanVal || stochVal <= stochMeanVal))

            // If we're not long, and there are outlier RSI and Stoch minimums in the last x hours,
            // go long. Similarly, if we're not short, and there are outlier RSI and Stoch maximums
            // in the last x hours, go short.
            if (state != Long &&
              now.minusMillis(2.hours.toMillis).isBefore(lastRsiMinOutlierTime) &&
              now.minusMillis(2.hours.toMillis).isBefore(lastStochMinOutlierTime)) {
              state = Long
              orderTargetRatio("bitfinex", md.product.toString, 1)
            } else if (state != Short &&
              now.minusMillis(2.hours.toMillis).isBefore(lastRsiMaxOutlierTime) &&
              now.minusMillis(2.hours.toMillis).isBefore(lastStochMaxOutlierTime)) {
              state = Short
              orderTargetRatio("bitfinex", md.product.toString, -1)
            } else if (shouldExit) {
              state = Neutral
              orderTargetRatio("bitfinex", md.product.toString, 0)
            }
          }
        }
      }

      lastRsiSlope = rsiSlopeVal
      lastStochSlope = stochSlopeVal

//      val minRsi = params.get.rsiThreshold
//      val maxRsi = 100 - minRsi
//      val minStoch = params.get.stochThreshold
//      val maxStoch = 100 - minStoch
//
//      if (!stochVal.isNaN) {
//        record("stoch", stochD.getValue(stochD.getTimeSeries.getEndIndex).doubleValue, md.micros)
//        orderTargetRatio("bitfinex", md.product.toString,
//          if (rsiVal <= minRsi && stochVal <= minStoch) {
//            state = Long
//            1
//          } else if (rsiVal >= maxRsi && stochVal >= maxStoch ) {
//            state = Short
//            -1
//          } else {
//            state = Neutral
//            0
//          })
//      }

  }


  override def resolveAddress(address: Address): Option[Iterator[MarketData]] = address match {
    case Address("bitfinex", product, "candles_1h") =>
      val pair = CurrencyPair(product)
      val pairStr = (pair.base + pair.quote).toUpperCase
      val it = Source
        .fromInputStream(getClass.getResourceAsStream("/data/" +
          ("Bitfinex" :: pairStr :: "1h" :: Nil).mkString("_") + ".csv"))
        .getLines

      var rows = Seq.empty[String]
      while (it.hasNext) {
        rows = rows :+ it.next
      }

      val headers = rows.drop(1).head.split(",")

      val dateFmt: Regex = raw"([0-9]{4})-([0-9]{2})-([0-9]{2}) ([0-9]{2})-(AM|PM)".r
      def parseDate(dateStr: String): Long = dateStr match {
        case dateFmt(year, month, day, hour, ampm) =>
          LocalDateTime
            .of(year.toInt, month.toInt, day.toInt, (hour.toInt % 12) + (ampm match {
              case "AM" => 0
              case "PM" => 12
            }), 0)
            .atOffset(ZoneOffset.UTC).toEpochSecond * 1000000
      }

      Some(rows.drop(2).reverse.map(headers zip _.split(","))
        .map(_.toMap)
        .map(props => CandleMD("bitfinex", pair.toString, Candle(
          parseDate(props("Date")),
          props("Open").toDouble,
          props("High").toDouble,
          props("Low").toDouble,
          props("Close").toDouble,
          None
        ))).iterator)

    case _ => None
  }
}
