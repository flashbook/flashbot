package object core {

  // TODO: Can we use refinement types to enforce the bounds?
  type Ratio = Double // Between -1 and 1 inclusive
  type Percent = Double // Between 0 and 1 inclusive

  trait BarUnit
  case object Seconds extends BarUnit
  case object Minutes extends BarUnit
  case object Hours extends BarUnit
  case object Days extends BarUnit
  case object Weeks extends BarUnit
  case object Months extends BarUnit

  case class BarSize(size: Int, unit: BarUnit)

  // Trading strategies always have a lower time bound, but not necessarily an upper one, in the
  // case of live trading.
  case class TimeRange(from: Long = 0, to: Option[Long] = None)

  sealed trait PairRole
  case object Base extends PairRole
  case object Quote extends PairRole

  case class Pair(base: String, quote: String) {
    override def toString: String = s"${base}_$quote"
    def toSeq: Seq[String] = List(base, quote)
  }

  case class Trade(price: Double, size: Double)

  case class CurrencyConfig(name: Option[String],
                            alias: Option[String])
}
