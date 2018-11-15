package io.flashbook.flashbot.engine

import akka.actor.Actor
import akka.cluster.ClusterEvent.{MemberRemoved, MemberUp, ReachableMember, UnreachableMember}
import akka.cluster.{Cluster, Member}

trait ClusterMemberManager extends Actor { slf =>
  def cluster: Cluster

  private var upMembers = Map.empty[Long, Member]
  private var reachableMembers = Map.empty[Long, Member]
  def members: Map[Long, Member] = upMembers.keySet
    .intersect(reachableMembers.keySet)
    .map(k => (k, upMembers(k)))
    .toMap

  override def receive = {
    case UnreachableMember(member) =>
      reachableMembers = reachableMembers - member.uniqueAddress.longUid
      slf.receive
    case ReachableMember(member) =>
      reachableMembers = reachableMembers + (member.uniqueAddress.longUid -> member)
      slf.receive
    case MemberUp(member) =>
      upMembers = upMembers + (member.uniqueAddress.longUid -> member)
      slf.receive
    case MemberRemoved(member, previousStatus) =>
      upMembers = upMembers - member.uniqueAddress.longUid
      slf.receive
    case other =>
      slf.receive
  }

}
