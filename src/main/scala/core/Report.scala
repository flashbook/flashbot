package core

import core.Report._
import io.circe.Json

import scala.concurrent.duration.Duration

case class Report(strategy: String,
                  params: String,
                  barSize: Duration,
                  trades: Vector[TradeEvent],
                  collections: Map[String, Vector[Json]],
                  timeSeries: Map[String, Vector[Candle]]) {

  def update(delta: ReportDelta): Report = delta match {
    case TradeAdd(tradeEvent) => copy(trades = trades :+ tradeEvent)
    case CollectionAdd(CollectionEvent(name, item)) => copy(collections = collections +
      (name -> (collections.getOrElse(name, Vector.empty[Json]) :+ item)))
    case event: CandleEvent => event match {
      case CandleSave(_, _) => this
      case CandleAdd(series, candle) =>
        copy(timeSeries = timeSeries + (series ->
          (timeSeries.getOrElse(series, Vector.empty) :+ candle)))
      case CandleUpdate(series, candle) =>
        copy(timeSeries = timeSeries + (series ->
          timeSeries(series).updated(timeSeries(series).length - 1, candle) ))
    }
  }

  def genDeltas(event: ReportEvent): Seq[ReportDelta] = event match {
    case tradeEvent: TradeEvent => TradeAdd(tradeEvent) :: Nil
    case collectionEvent: CollectionEvent => CollectionAdd(collectionEvent) :: Nil
    case e: PriceEvent => genTimeSeriesDelta[PriceEvent](
      List("price", e.exchange, e.product.toString).mkString("."), e, _.price)
    case e: BalanceEvent => genTimeSeriesDelta[BalanceEvent](
      List("balance", e.account.exchange, e.account.currency).mkString("."), e, _.balance)
    case e: TimeSeriesEvent => genTimeSeriesDelta[TimeSeriesEvent](e.key, e, _.value)
  }

  /**
    * Generates either a CandleSave followed by a CandleAdd, or a CandleUpdate by itself.
    */
  private def genTimeSeriesDelta[T <: Timestamped](series: String,
                                                   event: T,
                                                   valueFn: T => Double): Seq[ReportDelta] = {
    val value = valueFn(event)
    val newBarMicros = (event.micros / barSize.toMicros) * barSize.toMicros
    val currentTS = timeSeries.getOrElse(series, Vector.empty)
    if (currentTS.lastOption.exists(_.micros == newBarMicros)) {
      CandleUpdate(series, currentTS.last) :: Nil
    } else {
      val addList = CandleAdd(series, Candle(newBarMicros, value, value, value, value)) :: Nil
      if (currentTS.nonEmpty) CandleSave(series, currentTS.last) :: addList
      else addList
    }
  }
}

object Report {

  /**
    * Changes to reports need to be saved as data (engine events), so updates must happen in two
    * steps. First, a report receives a ReportEvent and emits a sequence of ReportDeltas. Then the
    * client code may do whatever it wants with the deltas. Probably fold them over the previous
    * report. Sometimes it will also persist the deltas.
    */
  sealed trait ReportDelta
  case class TradeAdd(tradeEvent: TradeEvent) extends ReportDelta
  case class CollectionAdd(collectionEvent: CollectionEvent) extends ReportDelta

  sealed trait CandleEvent extends ReportDelta {
    def series: String
  }
  case class CandleUpdate(series: String, candle: Candle) extends CandleEvent
  case class CandleSave(series: String, candle: Candle) extends CandleEvent
  case class CandleAdd(series: String, candle: Candle) extends CandleEvent

  case class Candle(micros: Long, open: Double, high: Double, low: Double, close: Double)
      extends Timestamped {
    def add(value: Double): Candle = copy(
      high = math.max(high, value),
      low = math.min(low, value),
      close = value
    )
  }

  /**
    * These are events that are emitted by the session, to be sent to the report.
    */
  sealed trait ReportEvent
  case class TradeEvent(id: Option[String],
                        exchange: String,
                        product: String,
                        micros: Long,
                        price: Double,
                        size: Double) extends ReportEvent with Timestamped
  case class PriceEvent(exchange: String,
                        product: Pair,
                        price: Double,
                        micros: Long) extends ReportEvent with Timestamped
  case class BalanceEvent(account: Account,
                          balance: Double,
                          micros: Long) extends ReportEvent with Timestamped
  case class TimeSeriesEvent(key: String, value: Double, micros: Long)
    extends ReportEvent with Timestamped
  case class CollectionEvent(name: String, item: Json) extends ReportEvent
}
