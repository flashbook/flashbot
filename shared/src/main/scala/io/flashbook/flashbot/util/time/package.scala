package io.flashbook.flashbot.util

import java.util.concurrent.TimeUnit.{DAYS, HOURS, MILLISECONDS, MINUTES, SECONDS}
import io.circe._
import io.circe.syntax._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.matching.Regex


package object time {
  private val msFmt: Regex = raw"([0-9]+)ms".r
  private val secondFmt: Regex = raw"([0-9]+)s".r
  private val minuteFmt: Regex = raw"([0-9]+)m".r
  private val hourFmt: Regex = raw"([0-9]+)h".r
  private val dayFmt: Regex = raw"([0-9]+)d".r

  def parseDurationOpt(str: String): Option[FiniteDuration] = str match {
    case msFmt(len: String) => Some(FiniteDuration(len.toInt, MILLISECONDS))
    case secondFmt(len: String) => Some(FiniteDuration(len.toInt, SECONDS))
    case minuteFmt(len: String) => Some(FiniteDuration(len.toInt, MINUTES))
    case hourFmt(len: String) => Some(FiniteDuration(len.toInt, HOURS))
    case dayFmt(len: String) => Some(FiniteDuration(len.toInt, DAYS))
    case _ => None
  }

  implicit def parseDuration(str: String): FiniteDuration = parseDurationOpt(str).get

  def printDurationOpt(d: FiniteDuration): Option[String] = (d.length, d.unit) match {
    case (n, MILLISECONDS) => Some(s"${n}ms")
    case (n, SECONDS) => Some(s"${n}s")
    case (n, MINUTES) => Some(s"${n}m")
    case (n, HOURS) => Some(s"${n}h")
    case (n, DAYS) => Some(s"${n}d")
  }

  def printDuration(d: FiniteDuration) = printDurationOpt(d).get

  implicit val durationEncoder: Encoder[Duration] = new Encoder[Duration] {
    override def apply(a: Duration) = a match {
      case fd: FiniteDuration => printDuration(fd).asJson
    }
  }

  implicit val durationDecoder: Decoder[Duration] = new Decoder[Duration] {
    override def apply(c: HCursor) = {
      val strDecoder = Decoder[String]
      strDecoder(c).right.map(parseDuration)
    }
  }

  def currentTimeMicros: Long = System.currentTimeMillis * 1000
}
