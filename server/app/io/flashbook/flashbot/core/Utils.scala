package io.flashbook.flashbot.core

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.TimeUnit.{DAYS, HOURS, MILLISECONDS, MINUTES, SECONDS}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.stream.scaladsl.Flow
import io.circe.{Decoder, Json, Printer}
import io.circe.parser._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.matching.Regex

//import ai.x.diff.DiffShow
//import ai.x.diff.conversions._

object Utils {

  private val formatter = DateTimeFormatter.ISO_DATE_TIME
  def ISO8601ToMicros(str: String): Long = {
    val inst = Instant.from(formatter.parse(str))
    inst.getEpochSecond * 1000000 + inst.getNano / 1000
  }

  def parseProductId(str: String): Pair = {
    var list = str.split("-")
    if (list.length == 1)
      list = str.split("_")
    Pair(list(0), list(1))
  }

  def parseJson[T](jsonStr: String)(implicit d: Decoder[T]): Either[String, T] = {
    parse(jsonStr) match {
      case Left(y) => Left("Could not parse JSON string: " ++ jsonStr)
      case Right(x) =>
        x.as[T] match {
          case Left(err) => Left(s"${err.message}: $jsonStr")
          case Right(ev) => Right(ev)
        }
    }
  }

  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)
  def printJson(json: Json): String = printer.pretty(json)

  def initResource[R, E](build: E => R): Flow[E, (R, E), NotUsed] =
    Flow[E].scan[Option[(R, E)]](None) {
      case (None, ev) => Some(build(ev), ev)
      case (Some((resource, _)), ev) => Some(resource, ev)
    }.drop(1).map(_.get)

  def deDupeWithSeq[T](seqFn: T => Long): Flow[T, T, NotUsed] = Flow[T]
    .scan[(Long, Option[T])](-1, None) {
    case ((seq, _), event) if seqFn(event) > seq => (seqFn(event), Some(event))
    case ((seq, _), _) => (seq, None)
  }.collect { case (_, Some(event)) => event }

  def deDupeVia[T](eqFn: (T, T) => Boolean): Flow[T, T, NotUsed] = Flow[T]
    .scan[(Option[T], Option[T])](None, None) { case ((_, b), ev) => (b, Some(ev)) }
    .collect( { case (last, Some(event)) if last.isEmpty || !eqFn(last.get, event)  => event })

  def deDupeBy[T, K](map: T => K): Flow[T, T, NotUsed] = deDupeVia[T]((a, b) => map(a) == map(b))

  def withIndex[T]: Flow[T, (Long, T), NotUsed] = Flow[T]
    .scan[(Long, Option[T])]((-1, None))((count, e) => (count._1 + 1, Some(e)))
    .drop(1)
    .map(e => (e._1, e._2.get))

  def buildMaterializer(implicit system: ActorSystem): ActorMaterializer =
    ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy { err =>
      println(s"Exception in stream: $err")
      throw err
      Supervision.Stop
    })

  val msFmt: Regex = raw"([0-9]+)ms".r
  val secondFmt: Regex = raw"([0-9]+)s".r
  val minuteFmt: Regex = raw"([0-9]+)m".r
  val hourFmt: Regex = raw"([0-9]+)h".r
  val dayFmt: Regex = raw"([0-9]+)d".r

  def parseDuration(str: String): FiniteDuration = str match {
    case msFmt(len: String) => FiniteDuration(len.toInt, MILLISECONDS)
    case secondFmt(len: String) => FiniteDuration(len.toInt, SECONDS)
    case minuteFmt(len: String) => FiniteDuration(len.toInt, MINUTES)
    case hourFmt(len: String) => FiniteDuration(len.toInt, HOURS)
    case dayFmt(len: String) => FiniteDuration(len.toInt, DAYS)
  }

  def currentTimeMicros: Long = System.currentTimeMillis * 1000

  private val timeFmt = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss")
  def formatDate(date: Date): String = timeFmt.format(date)

  val longVal: Regex = raw"^([0-9]+)$$".r
  val rmDot: Regex = raw"^([0-9]+)\.0+$$".r
  val doubleVal: Regex = raw"^([0-9]+)(\.[0-9]*[1-9])0*$$".r

  /**
    * Removes the trailing zeroes (and the period, if applicable) from the string representation
    * of a number.
    */
  def stripTrailingZeroes(d: String): String = d match {
    case longVal(v: String) => v
    case rmDot(v: String) => v
    case doubleVal(a: String, b: String) => a + b
  }
}
