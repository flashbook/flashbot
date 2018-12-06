package io.flashbook.flashbot.util

import akka.NotUsed
import akka.actor.{ActorContext, ActorPath, ActorRef, ActorSystem, RootActorPath}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.stream.scaladsl.{Flow, Source}
import akka.pattern.ask
import akka.util.Timeout
import io.flashbook.flashbot.core.{DataAddress, MarketData}
import io.flashbook.flashbot.engine.StreamResponse

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

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

  def iteratorToSource[T](it: Iterator[T])(implicit ec: ExecutionContext) = {
    Source.unfoldAsync[Iterator[T], T](it) { memo =>
      // TODO: Can we use Future.success and Future.failure here?
      // I have a hunch it was causing weird errors and that's why I left it like this.
      // ALSO: Why not use unfold instead of unfoldAsync? I think that wasn't quite working either.
      // Gotta double check.
      Future {
        if (memo.hasNext) {
          Some(memo, memo.next)
        } else {
          None
        }
      }
    }
  }

  implicit class IterOps[T](it: Iterator[T]) {
    def toSource(implicit ec: ExecutionContext): Source[T, NotUsed] = iteratorToSource(it)
  }

  def actorPathsAreLocal(a: ActorPath, b: ActorPath) =
    RootActorPath(a.address) == RootActorPath(b.address)

  def actorIsLocal(other: ActorRef)(implicit context: ActorContext) =
    RootActorPath(context.self.path.address) == RootActorPath(other.path.address)

  def senderIsLocal(implicit context: ActorContext) = actorIsLocal(context.sender)

  implicit def toActorPath(dataAddress: DataAddress): ActorPath =
    ActorPath.fromString(dataAddress.host.get)

  trait StreamRequest[T]

  implicit class StreamRequester(ref: ActorRef) {
    def <<?[T](req: StreamRequest[T]) = (ref ? req)(Timeout(10 seconds)) match {
      case fut: Future[StreamResponse[T]] => fut
    }
  }

}
