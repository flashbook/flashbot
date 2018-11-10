package io.flashbook.flashbot.server

import akka.actor.{Actor, ActorRef, Props}
import io.circe.Printer
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.parser._
import io.flashbook.flashbot.util.json._

object BotSocket {
  sealed trait Req
  sealed trait Rsp

  def props(out: ActorRef) = Props(new BotSocket(out))
}

class BotSocket(out: ActorRef) extends Actor {
  import BotSocket._

  def receive = {
    case str: String => parseJson[Req](str) match {
      case Left(error) => ???
      case Right(req) => ???
    }
  }

  def respond(rsp: Rsp): Unit = {
    out ! printJson(rsp)
  }
}
