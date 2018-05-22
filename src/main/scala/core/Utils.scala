package core

import java.time.Instant
import java.time.format.DateTimeFormatter

import io.circe.Decoder
import io.circe.parser._

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
}
