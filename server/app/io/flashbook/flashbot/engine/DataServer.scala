package io.flashbook.flashbot.engine

import akka.actor.{Actor, ActorLogging}
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.{Cluster, Member}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * A DataServer runs and manages a set of data sources.
  */
class DataServer() extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = context.system.dispatcher
  context.system.scheduler.schedule(1 second, 1 second, self, "tick")

  override def receive = {
    case a =>
      log.info(a.toString)
//      log.info(members.values.map(m => s"${m.address} (${m.uniqueAddress})").mkString(", "))
  }
}
