package io.flashbook.flashbot.util


import java.util.concurrent.TimeUnit.{DAYS, HOURS, MILLISECONDS, MINUTES, SECONDS}
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex


package object time {
  val msFmt: Regex = raw"([0-9]+)ms".r
  val secondFmt: Regex = raw"([0-9]+)s".r
  val minuteFmt: Regex = raw"([0-9]+)m".r
  val hourFmt: Regex = raw"([0-9]+)h".r
  val dayFmt: Regex = raw"([0-9]+)d".r

  def parseDurationOpt(str: String): Option[FiniteDuration] = str match {
    case msFmt(len: String) => Some(FiniteDuration(len.toInt, MILLISECONDS))
    case secondFmt(len: String) => Some(FiniteDuration(len.toInt, SECONDS))
    case minuteFmt(len: String) => Some(FiniteDuration(len.toInt, MINUTES))
    case hourFmt(len: String) => Some(FiniteDuration(len.toInt, HOURS))
    case dayFmt(len: String) => Some(FiniteDuration(len.toInt, DAYS))
  }

  def parseDuration(str: String): FiniteDuration = parseDurationOpt(str).get

  def currentTimeMicros: Long = System.currentTimeMillis * 1000
}
