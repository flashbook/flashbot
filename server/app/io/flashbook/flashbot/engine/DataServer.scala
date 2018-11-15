package io.flashbook.flashbot.engine

import akka.actor.{Actor, ActorLogging}
import akka.cluster.{Cluster, Member}

class DataServer() extends Actor with ActorLogging with ClusterMemberManager {
  val cluster = Cluster(context.system)

  override def receive = {
    case a =>
      log.info(a.toString)
      log.info(members.values.map(m => s"${m.address} (${m.uniqueAddress})").mkString(", "))
  }
}
