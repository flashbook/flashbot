package io.flashbook.flashbot.util

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.stream.scaladsl.Flow

package object stream {

  def initResource[R, E](build: E => R): Flow[E, (R, E), NotUsed] =
    Flow[E].scan[Option[(R, E)]](None) {
      case (None, ev) => Some(build(ev), ev)
      case (Some((resource, _)), ev) => Some(resource, ev)
    }.drop(1).map(_.get)

  def deDupeWithSeq[T](seqFn: T => Long): Flow[T, T, NotUsed] = Flow[T]
    .scan[(Long, Option[T])](-1, None) {
    case ((seq, _), event) if seqFn(event) > seq => (seqFn(event), Some(event))
    case ((seq, _), _) => (seq, None)
  }.collect { case (_, Some(event)) => event }

  def deDupeVia[T](eqFn: (T, T) => Boolean): Flow[T, T, NotUsed] = Flow[T]
    .scan[(Option[T], Option[T])](None, None) { case ((_, b), ev) => (b, Some(ev)) }
    .collect( { case (last, Some(event)) if last.isEmpty || !eqFn(last.get, event)  => event })

  def deDupeBy[T, K](map: T => K): Flow[T, T, NotUsed] = deDupeVia[T]((a, b) => map(a) == map(b))

  def withIndex[T]: Flow[T, (Long, T), NotUsed] = Flow[T]
    .scan[(Long, Option[T])]((-1, None))((count, e) => (count._1 + 1, Some(e)))
    .drop(1)
    .map(e => (e._1, e._2.get))

  def buildMaterializer(implicit system: ActorSystem): ActorMaterializer =
    ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy { err =>
      println(s"Exception in stream: $err")
      throw err
      Supervision.Stop
    })


}
