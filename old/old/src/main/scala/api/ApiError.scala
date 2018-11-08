package api

import io.circe.Json
import io.circe.syntax._
import io.circe.generic.auto._
import sangria.parser.SyntaxError

sealed trait ApiError

object ApiError {
  sealed case class ApiErrorRsp(errors: Seq[ApiError])

  final case class SyntaxApiError(message: String, locations: Seq[(Long, Long)]) extends ApiError
  final case class ServerApiError(message: String) extends ApiError
  final case class BadDataError(message: String, fields: Map[String, String]) extends ApiError

  def format(msg: String): Json = ApiErrorRsp(List(ServerApiError(msg))).asJson

  def format(err: Throwable): Json = err match {
    case e: SyntaxError => ApiErrorRsp(
      List(SyntaxApiError(e.getMessage,
        List((e.originalError.position.line, e.originalError.position.column))))).asJson
    case e => throw e
  }

}

