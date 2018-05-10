package object core {
  trait BarUnit
  case object Seconds extends BarUnit
  case object Minutes extends BarUnit
  case object Hours extends BarUnit
  case object Days extends BarUnit
  case object Weeks extends BarUnit
  case object Months extends BarUnit

  case class BarSize(size: Int, unit: BarUnit)

  // nanos
  case class TimeRange(from: Long, to: Long)

  case class Pair(base: String, quote: String) {
    override def toString: String = s"${base}_$quote"
  }

  case class Trade(price: Double, size: Double)
}
