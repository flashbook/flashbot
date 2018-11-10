package io.flashbook.flashbot

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.auto._
import io.flashbook.flashbot.core.AggBook.AggBook
import io.flashbook.flashbot.core.{Account, CanDeltaUpdate, Candle, Timestamped, Pair}

import scala.concurrent.duration._

package object report {

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
  case class CandleAdd(series: String, candle: Candle) extends CandleEvent

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

  case class TimeSeriesCandle(key: String, candle: Candle)
    extends ReportEvent with Timestamped {
    override def micros: Long = candle.micros
  }

  // A ValueEvent is both a ReportEvent and a ReportDelta
  sealed trait ValueEvent extends ReportEvent with ReportDelta
  case class PutValueEvent(key: String, value: ReportValue[_]) extends ValueEvent
  case class UpdateValueEvent(key: String, delta: Json) extends ValueEvent
  case class RemoveValueEvent(key: String) extends ValueEvent

  case class CollectionEvent(name: String, item: Json) extends ReportEvent

  object Report {
    def empty(strategyName: String,
              params: Json,
              barSize: Option[Duration] = None): Report = Report(
      strategyName,
      params,
      barSize.getOrElse(1 minute),
      Vector(),
      Map.empty,
      Map.empty,
      Map.empty
    )
  }

  case class Report(strategy: String,
                    params: Json,
                    barSize: Duration,
                    trades: Vector[TradeEvent],
                    collections: Map[String, Vector[Json]],
                    timeSeries: Map[String, Vector[Candle]],
                    values: Map[String, ReportValue[_]]) extends CanDeltaUpdate {

    override type Delta = ReportDelta

    override def deltaEncoder = implicitly[Encoder[ReportDelta]]
    override def deltaDecoder = implicitly[Decoder[ReportDelta]]

    def update(delta: ReportDelta): Report = delta match {
      case TradeAdd(tradeEvent) => copy(trades = trades :+ tradeEvent)
      case CollectionAdd(CollectionEvent(name, item)) => copy(collections = collections +
        (name -> (collections.getOrElse(name, Vector.empty[Json]) :+ item)))
      case event: CandleEvent => event match {
        case CandleAdd(series, candle) =>
          copy(timeSeries = timeSeries + (series ->
            (timeSeries.getOrElse(series, Vector.empty) :+ candle)))
        case CandleUpdate(series, candle) =>
          copy(timeSeries = timeSeries + (series ->
            timeSeries(series).updated(timeSeries(series).length - 1, candle) ))
      }
      case event: ValueEvent => copy(values = event match {
        case PutValueEvent(key, value: ReportValue[_]) =>
          values + (key -> value)
        case e: UpdateValueEvent =>
          values + (e.key -> (values(e.key) match {
            case dv: DeltaValue[_] =>
              dv.update(dv.deltaDecoder.decodeJson(e.delta).right.get)
          }))
        case RemoveValueEvent(key) =>
          values - key
      })
    }

    def genDeltas(event: ReportEvent): Seq[ReportDelta] = event match {
      case tradeEvent: TradeEvent =>
        TradeAdd(tradeEvent) :: Nil

      case collectionEvent: CollectionEvent =>
        CollectionAdd(collectionEvent) :: Nil

      case e: PriceEvent =>
        genTimeSeriesDelta[PriceEvent](
          List("price", e.exchange, e.product.toString).mkString("."), e, _.price) :: Nil

      case e: BalanceEvent =>
        genTimeSeriesDelta[BalanceEvent](
          List("balance", e.account.exchange, e.account.currency).mkString("."), e, _.balance) :: Nil

      case e: TimeSeriesEvent =>
        genTimeSeriesDelta[TimeSeriesEvent](e.key, e, _.value) :: Nil

      case e: TimeSeriesCandle =>
        CandleAdd(e.key, e.candle) :: Nil

      case e: ValueEvent => List(e)
    }

    /**
      * Generates either a CandleSave followed by a CandleAdd, or a CandleUpdate by itself.
      */
    private def genTimeSeriesDelta[T <: Timestamped](series: String,
                                                     event: T,
                                                     valueFn: T => Double): ReportDelta = {
      val value = valueFn(event)
      val newBarMicros = (event.micros / barSize.toMicros) * barSize.toMicros
      val currentTS: Seq[Candle] = timeSeries.getOrElse(series, Vector.empty)
      if (currentTS.lastOption.exists(_.micros == newBarMicros))
        CandleUpdate(series, currentTS.last.add(value))
      else
        CandleAdd(series, Candle(newBarMicros, value, value, value, value))
    }
  }

  /**
    * ReportValues are stored on disk with default Java serialization.
    * They may also be transferred over the network via JSON.
    */
  sealed trait ReportValue[T] {
    def value: T
  }

  sealed abstract class DeltaValue[V <: CanDeltaUpdate](value: V) extends ReportValue[V] with CanDeltaUpdate {
    override type Delta = value.Delta
    override def deltaEncoder: Encoder[Delta] = value.deltaEncoder
    override def deltaDecoder: Decoder[Delta] = value.deltaDecoder
  }

  case class AggBookValue(value: AggBook) extends DeltaValue(value) {
    override def update(delta: Delta) = copy(value = value.update(delta))
  }

  case class JsonValue(value: Json) extends ReportValue[Json]
}
