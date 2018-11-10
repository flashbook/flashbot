package io.flashbook.flashbot.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.TimeUnit.{DAYS, HOURS, MILLISECONDS, MINUTES, SECONDS}

import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

package object time {

  private val formatter = DateTimeFormatter.ISO_DATE_TIME
  def ISO8601ToMicros(str: String): Long = {
    val inst = Instant.from(formatter.parse(str))
    inst.getEpochSecond * 1000000 + inst.getNano / 1000
  }

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
}
