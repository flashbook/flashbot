package core

import java.time.Instant
import java.time.format.DateTimeFormatter

import akka.NotUsed
import akka.stream.scaladsl.Flow
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

  def initResource[R, E](build: E => R): Flow[E, (R, E), NotUsed] =
    Flow[E].scan[Option[(R, E)]](None) {
      case (None, ev) => Some(build(ev), ev)
      case (Some((resource, _)), ev) => Some(resource, ev)
    }.map(_.get)

  def deDupeStream[T](seqFn: T => Long): Flow[T, T, NotUsed] = Flow[T]
    .scan[(Long, Option[T])]((-1, None)) {
    case ((seq, _), event) if seqFn(event) > seq => (seqFn(event), Some(event))
    case ((seq, _), _) => (seq, None)
  }.collect { case (_, Some(event)) => event }
}
