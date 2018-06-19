package api

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import core.TradingEngine

import scala.concurrent.duration._
import core.TradingEngine.{Command, Ping, Pong, Query, Response}

import scala.concurrent.Await

class UserCtx(engine: ActorRef) {
  implicit val timeout: Timeout = Timeout(10 minutes)

  def ping: String = request[Pong.type](Ping).toString

  def request[T <: Response](query: Query): T = {
    Await.result(engine ? query, timeout.duration).asInstanceOf[T]
  }

  def request[T <: Response](command: Command): T = {
    Await.result(engine ? command, timeout.duration).asInstanceOf[T]
  }
}
