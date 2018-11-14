package io.flashbook.flashbot.api

import akka.actor.{Actor, ActorRef, Props}

object BotSocket {
  sealed trait Req
  sealed trait Rsp

  def props(out: ActorRef) = Props(new BotSocket(out))
}

class BotSocket(out: ActorRef) extends Actor {
  import BotSocket._

  def receive = {
    case str: String =>
//      parseJson[Req](str) match {
//        case Left(error) => ???
//        case Right(req) => ???
//      }
  }

  def respond(rsp: Rsp): Unit = {
//    out ! printJson(rsp)
  }
}
