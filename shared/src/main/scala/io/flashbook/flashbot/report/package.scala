package io.flashbook.flashbot

import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.auto._
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.flashbook.flashbot.core._
import scala.concurrent.duration._

package object report {


  val putValueEvJsonEn: Encoder[PutValueEvent[Json]] = deriveEncoder
  val putValueEvJsonDe: Decoder[PutValueEvent[Json]] = deriveDecoder
  val updateValueEvJsonEn: Encoder[UpdateValueEvent[Json]] = deriveEncoder
  val updateValueEvJsonDe: Decoder[UpdateValueEvent[Json]] = deriveDecoder

  implicit def valueEventEn(implicit report: Report): Encoder[ValueEvent] = new Encoder[ValueEvent] {
    override def apply(a: ValueEvent): Json = a match {
      case ev @ PutValueEvent(key, fmtName, value) =>
        val putValueEventJson: PutValueEvent[Json] =
          ev.copy(value = putValEn(VarFmt.formats(fmtName), value))
        putValueEvJsonEn(putValueEventJson)

      case ev @ UpdateValueEvent(key, delta) =>
        val fmt = VarFmt.formats(report.values(key).fmtName)
        val updateValueEventJson: UpdateValueEvent[Json] =
          ev.copy(delta = updateDeltaEn(fmt, delta))
        updateValueEvJsonEn(updateValueEventJson)

      case ev: RemoveValueEvent =>
        val en = implicitly[Encoder[RemoveValueEvent]]
        en(ev)
    }

    def putValEn[T](fmt: VarFmt[T], value: Any): Json = {
      value match {
        case typedValue: T =>
          fmt.modelEn(typedValue)
      }
    }

    def updateDeltaEn[T](fmt: VarFmt[T], delta: Any): Json = {
      delta match {
        case typedDelta: fmt.D =>
          fmt.deltaEn(typedDelta)
      }
    }
  }

  implicit def valueEventDe(implicit report: Report): Decoder[ValueEvent] = new Decoder[ValueEvent] {
    override def apply(c: HCursor): Result[ValueEvent] = {
      val rmDecoder = implicitly[Decoder[RemoveValueEvent]]

      val updateDecoder = implicitly[Decoder[UpdateValueEvent[Json]]]

      // Otherwise, must decode as PutValueEvent
      val putDecoder = implicitly[Decoder[PutValueEvent[Json]]]

      // Try to decode as RemoveValueEvent
      rmDecoder(c) match {
        // Try to decode as UpdateValueEvent
        case Left(_) => updateDecoder(c) match {
          case Left(_) => putDecoder(c)
          case right => right
        }
        case right => right
      }
    }
  }

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

  sealed trait ValueEvent extends ReportEvent with ReportDelta
  case class PutValueEvent[T](key: String, fmtName: String, value: T) extends ValueEvent
  case class UpdateValueEvent[T](key: String, delta: T) extends ValueEvent
  case class RemoveValueEvent(key: String) extends ValueEvent

  implicit def reportDeltaEn(implicit report: Report): Encoder[ReportDelta] = {
    implicit val veEn: Encoder[ValueEvent] = valueEventEn
    implicitly[Encoder[ReportDelta]]
  }

  implicit def reportDeltaDe(implicit report: Report): Decoder[ReportDelta] = {
    implicit val veDe: Decoder[ValueEvent] = valueEventDe
    implicitly[Decoder[ReportDelta]]
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

  case class TimeSeriesCandle(key: String, candle: Candle)
    extends ReportEvent with Timestamped {
    override def micros: Long = candle.micros
  }

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

  type ValuesMap = Map[String, ReportValue[_]]

  implicit def vMapEn: Encoder[ValuesMap] = new Encoder[ValuesMap] {
    override def apply(a: ValuesMap) = {
      val jsonMap: Map[String, Json] = a.foldLeft(Map.empty[String, Json])((memo, kv) => {
        memo + (kv._1 -> rvEncode(kv._2))
      })
      jsonMap.asJson
    }

    def rvEncode[T](rv: ReportValue[T]): Json = {
      VarFmt.formats(rv.fmtName) match {
        case fmt: VarFmt[T] =>
          fmt.modelEn(rv.value)
      }
    }
  }

  implicit val rvJsonDecoder: Decoder[ReportValue[Json]] = deriveDecoder[ReportValue[Json]]

  implicit def vMapDe: Decoder[ValuesMap] = new Decoder[ValuesMap] {
    override def apply(c: HCursor) = {
      c.as[Map[String, Json]].right.map(_.mapValues(reportVal))
    }

    def reportVal(obj: Json): ReportValue[_] =
      rvJsonDecoder.decodeJson(obj) match {
        case Right(ReportValue(fmtName, value)) =>
          ReportValue(fmtName, VarFmt.formats(fmtName).modelDe.decodeJson(value).right.get)
      }
  }

  case class Report(strategy: String,
                    params: Json,
                    barSize: Duration,
                    trades: Vector[TradeEvent],
                    collections: Map[String, Vector[Json]],
                    timeSeries: Map[String, Vector[Candle]],
                    values: ValuesMap) {

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
        case PutValueEvent(key, fmtName, anyValue) =>
          val fmt = VarFmt.formats(fmtName)
          setVal(fmt, key, anyValue, values)

        case UpdateValueEvent(key, anyDelta) =>
          val fmt = VarFmt.formats(values(key).fmtName)
          updateVal(fmt, key, anyDelta, values)

        case RemoveValueEvent(key) =>
          values - key
      })
    }

    private def setVal[T](fmt: VarFmt[T], key: String, value: Any,
                          values: Map[String, ReportValue[_]]): Map[String, ReportValue[_]] = {
      val tv = value.asInstanceOf[T]
      values + (key -> ReportValue(fmt.fmtName, tv))
    }

    private def updateVal[T](fmt: VarFmt[T], key: String, delta: Any,
                                             values: Map[String, ReportValue[_]]): Map[String, ReportValue[_]] = {
      val dv = delta.asInstanceOf[fmt.D]
      val v = values(key).asInstanceOf[T]
      val newVal = fmt.incUpdate(v, dv)
      values + (key -> ReportValue(fmt.fmtName, newVal))
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


//  trait CanDeltaUpdate[V] {
//    type Delta
//    def update(delta: Delta): V
//  }


  /**
    * ReportValues are stored on disk with default Java serialization.
    * They may also be transferred over the network via JSON.
    */
  case class ReportValue[T](fmtName: String, value: T)


//  def reportVal[V](v: V)(implicit en: Encoder[V],
//                                  de: Decoder[V]): ReportValue[V] = new ReportValue[V] {
//    val value = v
//    val encode = en
//    val decoder = de
//  }

//  sealed trait DeltaValue[D, V <: CanDeltaUpdate[V] { type Delta = D }]
//        extends CanDeltaUpdate[DeltaValue[D, V]] with ReportValue[V] {
//    override type Delta = D
//    def value: V
//    implicit def deltaEncoder: Encoder[Delta]
//    implicit def deltaDecoder: Decoder[Delta]
//  }
//
//  case class AggBookValue(value: AggBook.AggBook) extends DeltaValue[AggBook.AggDelta, AggBook.AggBook] {
//    override def update(delta: Delta) = copy(value = value.update(delta))
//    override def deltaEncoder = AggBook.AggDelta.en
//    override def deltaDecoder = AggBook.AggDelta.de
//  }

}
