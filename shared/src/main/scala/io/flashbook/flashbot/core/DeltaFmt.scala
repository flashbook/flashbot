package io.flashbook.flashbot.core

import io.circe.{Decoder, Encoder}

/**
  * A type class for event sourcing data that supports incremental updates. Rationale is that we
  * have some data types that are quite large and frequently updating. The data types themselves
  * usually handle this well on their own by providing efficient updater methods. However,
  * streaming them over the network is still not accounted for. We need to formalize the
  * incremental update model by using event sourcing.
  *
  * Create a data type that represents a single self contained modification to the model. Ensure
  * that it's Json serializable, as well as the model type itself. The Delta data type should be
  * more or less a sealed trait encoding the public update API of the model.
  *
  * It's also worth noting that we can't always just hook into the event stream of market data
  * sources because a lot of the data that we'll be emitting over the network is *not* market data.
  * Rather, it's computed in the strategy, so it won't always have a change history. I think the
  * best way to do this is to define the Model and Delta data sources and a function that computes
  * the delta diff of two versions of the same model. And if the model wants to implement this diff
  * via internal change stream that's sourced from somewhere else, that's splendid.
  *
  * The Scala.js app will use this to recreate state on the React side for vars.
  */
trait DeltaFmt[M] <: FoldFmt[M] {
  type D
  def fmtName: String
  def update(model: M, delta: D): M
  def diff(prev: M, current: M): Seq[D]
}

trait DeltaFmtJson[M] <: DeltaFmt[M] {
  def modelEn: Encoder[M]
  def modelDe: Decoder[M]
  def deltaEn: Encoder[D]
  def deltaDe: Decoder[D]
}

object DeltaFmt {
  /**
   * The default type class simply doesn't have a diffing mechanism. It overrides the Delta type
   * to be the same type as the model, and it tricks the persistence engine into actually saving
   * the updated model in full after doing a diff.
   */
  def defaultFmtJson[M](name: String)
                   (implicit en: Encoder[M],
                    de: Decoder[M]): DeltaFmtJson[M] = new DeltaFmtJson[M] {
    override type D = M
    override def fmtName: String = name
    override def update(model: M, delta: D): M = delta
    override def diff(prev: M, current: M): Seq[D] = Seq(current)
    override def modelEn: Encoder[M] = en
    override def modelDe: Decoder[M] = de
    override def deltaEn: Encoder[D] = en
    override def deltaDe: Decoder[D] = de

    override def fold(x: M, y: M) = y
    override def unfold(x: M) = (x, None)
  }

  def default[M](name: String): DeltaFmt[M] = new DeltaFmt[M] {
    override type D = M
    override def fmtName: String = name
    override def update(model: M, delta: D): M = delta
    override def diff(prev: M, current: M): Seq[D] = Seq(current)
    override def fold(x: M, y: M) = y
    override def unfold(x: M) = (x, None)
  }

  implicit val intVarFmt: DeltaFmtJson[java.lang.Integer] = defaultFmtJson("int")
  implicit val doubleVarFmt: DeltaFmtJson[java.lang.Double] = defaultFmtJson("double")
  implicit val stringVarFmt: DeltaFmtJson[java.lang.String] = defaultFmtJson("string")
  implicit val booleanVarFmt: DeltaFmtJson[java.lang.Boolean] = defaultFmtJson("boolean")

  val varFmtSet: Set[DeltaFmtJson[_]] = Set(
    implicitly[DeltaFmtJson[java.lang.Integer]],
    implicitly[DeltaFmtJson[java.lang.Double]],
    implicitly[DeltaFmtJson[java.lang.String]],
    implicitly[DeltaFmtJson[java.lang.Boolean]],
  )

  // We need to have an index of DeltaFmtJson instances. It would be great to do without this,
  // but for now this works.
  val formats = varFmtSet.foldLeft(Map.empty[String, DeltaFmtJson[_]])((memo, item) =>
    memo + (item.fmtName -> item))

  def apply[T: DeltaFmtJson]: DeltaFmtJson[T] = implicitly[DeltaFmtJson[T]]

  implicit class FmtStringOps(str: String) {
    def fmt: DeltaFmt[_] = DataType.parse(str) match {
      case Some(dt) => dt.fmt
      case None => formats(str)
    }

//    def fmtJson: DeltaFmtJson[_] = DataType.parse(str) match {
//      case Some(dt) => dt.fmt
//      case None => formats(str)
//    }
  }
}

