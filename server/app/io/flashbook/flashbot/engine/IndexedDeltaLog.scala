package io.flashbook.flashbot.engine

import java.io.File

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.flashbook.flashbot.core.DataSource.Bundle
import io.flashbook.flashbot.core.{DeltaFmt, FoldFmt, Timestamped}
import io.flashbook.flashbot.engine.IndexedDeltaLog._

import scala.concurrent.duration._

/**
  * A wrapper around TimeLog that manages continuous bundles of data and stores
  * it efficiently using DeltaFmt. It also takes periodic snapshots and is able
  * to return an index of its data bundles.
  */
class IndexedDeltaLog[T](path: File,
                         retention: Duration,
                         sliceSize: Duration)
                        (implicit fmt: DeltaFmt[T]) {

  implicit private val tDe: Decoder[T] = fmt.modelDe
  implicit private val tEn: Encoder[T] = fmt.modelEn
  implicit private val dDe: Decoder[fmt.D] = fmt.deltaDe
  implicit private val dEn: Encoder[fmt.D] = fmt.deltaEn
  implicit val de: Decoder[BundleWrapper] = deriveDecoder
  implicit val en: Encoder[BundleWrapper] = deriveEncoder

  val timeLog = TimeLog[BundleWrapper](path, retention)
  val prevBundleLastItem = timeLog.last
  val currentBundle = prevBundleLastItem.map(_.bundle).getOrElse(-1L) + 1
  var currentSlice = -1
  var lastSeenTime = prevBundleLastItem.map(_.micros).getOrElse(-1L)
  var lastData: Option[T] = None

  def save(micros: Long, data: T): Unit = {
    if (micros < lastSeenTime) {
      throw new RuntimeException("IndexedDeltaLog does not support outdated data.")
    }

    var wrappers = Seq.empty[BundleWrapper]

    // If it's time for a new slice, unfold the data and save snapshot.
    // Also increment the current slice.
    if (lastData.isEmpty || (micros - lastSeenTime) >= sliceSize.toMicros) {
      currentSlice = currentSlice + 1
      val unfolded = FoldFmt.unfoldData(data)
      wrappers ++= unfolded.zipWithIndex.map { case (d, i) =>
        BundleSnap(currentBundle, currentSlice, micros, i == 0, i == unfolded.size - 1,
          Some(lastSeenTime), d.asJson)
      }
    }

    // And now we generate and save a delta against the previous item in this bundle.
    if (lastData.isDefined) {
      val deltas = fmt.diff(lastData.get, data)
      wrappers ++= deltas.map(delta =>
        BundleDelta(currentBundle, currentSlice, micros, delta.asJson))
    }

    // Persist to time log
    wrappers.foreach(timeLog.save(_))

    // Update vars
    lastSeenTime = micros
    lastData = Some(data)
  }

//  def scan

  def index: Map[Long, Bundle] = {
    var idx = Map.empty[Long, Bundle]
    var currentSliceHead = timeLog.first

    if (currentSliceHead.isDefined) {
      // We have a slice id, but we're not sure if it represents a full slice.
      // The first slice id we find may belong to a slice that has had it's slice
      // snapshot deleted due to retention, but still has some lingering deltas at
      // the start of the log. Search for the starting and ending snap for the slice.
      val snapStart = findSnapStart(currentSliceHead.get.sliceId)
      val snapEnd = findSnapEnd(currentSliceHead.get.sliceId)

      if (!(snapStart.isDefined && snapEnd.isDefined)) {
        // If the snapshot is not valid, find the next slice id.
        currentSliceHead = findNextSlice(currentSliceHead.get.sliceId)
          .orElse(findNextBundle(currentSliceHead.get.sliceId))
      }
    }

    // Foreach initial slice of all bundles find the time of the last item of the bundle.
    while (currentSliceHead.nonEmpty) {

      // Find the start of the next bundle.
      val nextSliceItem = findNextBundle(currentSliceHead.get.sliceId)

      // If there is no next bundle, then the last item of the log must belong to the
      // current bundle. Grap the very last item, ensure it belongs to the current bundle
      // and populate the index with its time.
      var endTime: Long = -1
      if (nextSliceItem.isDefined) {
        endTime = nextSliceItem.get.prevSliceEndMicros.get
      } else {
        val lastItem = timeLog.last.get
        if (lastItem.bundle != currentSliceHead.get.bundle) {
          throw new RuntimeException(s"Expected bundle id ${currentSliceHead.get.bundle}. " +
            s"Got ${lastItem.bundle}.")
        }
        endTime = lastItem.micros
      }

      // Update the index and currentSliceHead
      idx += (currentSliceHead.get.bundle ->
        Bundle(currentSliceHead.get.bundle, currentSliceHead.get.micros, endTime))

      currentSliceHead = nextSliceItem
    }

    idx
  }

  def findSnapStart(id: SliceId): Option[BundleSnap] =
    timeLog.find[(SliceId, Option[SnapBound])](
      (id, Some(Start)), b => (b.sliceId, b.matchBound(Start))
    )(de, sliceBoundOrder).map(_.asInstanceOf[BundleSnap])

  def findSnapEnd(id: SliceId): Option[BundleSnap] =
    timeLog.find[(SliceId, Option[SnapBound])](
      (id, Some(End)), b => (b.sliceId, b.matchBound(End))
    )(de, sliceBoundOrder).map(_.asInstanceOf[BundleSnap])

  def findNextSlice(current: SliceId): Option[BundleSnap] =
    findSnapStart(current.nextSlice)

  def findNextBundle(current: SliceId): Option[BundleSnap] =
    findSnapStart(current.nextBundle)


  def compareSlice(x: SliceId, y: SliceId): Int = {
    if (x.bundle < y.bundle) -1
    else if (x.bundle > y.bundle) 1
    else {
      if (x.slice < y.slice) -1
      else if (x.slice > y.slice) 1
      else 0
    }
  }

  val sliceBoundOrder: Ordering[(SliceId, Option[SnapBound])] =
    new Ordering[(SliceId, Option[SnapBound])] {
      override def compare(x: (SliceId, Option[SnapBound]), y: (SliceId, Option[SnapBound])) = {
        compareSlice(x._1, y._1) match {
          case 0 => (x._2, y._2) match {
            case (None, Some(_)) => 1
            case (Some(a), Some(b)) if a == b => 0
            case (Some(Other), Some(Start)) => 1
            case (Some(Other), Some(End)) => -1
          }
          case other => other
        }
      }
    }

}

object IndexedDeltaLog {

  sealed trait SnapBound
  case object Start extends SnapBound
  case object End extends SnapBound
  case object Other extends SnapBound

  sealed trait BundleWrapper extends Timestamped {
    def bundle: Long
    def slice: Long
    def sliceId: SliceId = SliceId(bundle, slice)

    def matchBound(bound: SnapBound): Option[SnapBound] = {
      (this, bound) match {
        case (bs: BundleSnap, Start) => Some(if (bs.isStart) Start else Other)
        case (bs: BundleSnap, End) => Some(if (bs.isEnd) End else Other)
        case _ => None
      }
    }
  }
  case class BundleSnap(bundle: Long, slice: Long, micros: Long, isStart: Boolean, isEnd: Boolean,
                        prevSliceEndMicros: Option[Long], snap: Json) extends BundleWrapper
  case class BundleDelta(bundle: Long, slice: Long, micros: Long,
                         delta: Json) extends BundleWrapper

  case class SliceId(bundle: Long, slice: Long) {
    def nextSlice = SliceId(bundle, slice + 1)
    def nextBundle = SliceId(bundle + 1, 0)
  }
}
