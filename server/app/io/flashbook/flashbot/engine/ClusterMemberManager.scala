package io.flashbook.flashbot.engine

import akka.actor.Actor
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member}

class ClusterMemberManager extends Actor {
  val cluster: Cluster = Cluster(context.system)
//  cluster.join(cluster.selfAddress)
//  cluster.registerOnMemberUp()

  private var upMembers = Map.empty[Long, Member]
  private var reachableMembers = Map.empty[Long, Member]
  def members: Map[Long, Member] = upMembers.keySet
    .intersect(reachableMembers.keySet)
    .map(k => (k, upMembers(k)))
    .toMap

  override def preStart(): Unit = {
    println("SUBSCRIBING")
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[ReachabilityEvent])
    this.preStart()
  }

  override def postStop(): Unit = {
    println("UNSUB")
    cluster.unsubscribe(self)
    this.postStop()
  }


  override def receive = {
    case msg: MemberEvent => msg match {
      case MemberUp(member) =>
        upMembers = upMembers + (member.uniqueAddress.longUid -> member)
      case MemberRemoved(member, previousStatus) =>
        upMembers = upMembers - member.uniqueAddress.longUid
      case _ =>
    }
  }
}
