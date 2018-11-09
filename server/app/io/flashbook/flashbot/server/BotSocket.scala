package io.flashbook.flashbot.server

import akka.actor.{Actor, ActorRef, Props}

object BotSocket {
  def props(out: ActorRef) = Props(new BotSocket(out))
}

class BotSocket(out: ActorRef) extends Actor {
  def receive = {
    case "foo" => out ! "hi"
  }
}
