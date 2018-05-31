import core.MarketData.GenMD
import core.Utils.parseProductId

import io.circe.generic.auto._

package object core {

  // TODO: Can we use refinement types to enforce the bounds?
  type Ratio = Double // Between -1 and 1 inclusive
  type Percent = Double // Between 0 and 1 inclusive

  sealed trait BarUnit
  case object Seconds extends BarUnit
  case object Minutes extends BarUnit
  case object Hours extends BarUnit
  case object Days extends BarUnit
  case object Weeks extends BarUnit
  case object Months extends BarUnit

  case class BarSize(size: Int, unit: BarUnit)

  case class TimeRange(from: Long = 0, to: Long = Long.MaxValue)

  sealed trait PairRole
  case object Base extends PairRole
  case object Quote extends PairRole

  case class Pair(base: String, quote: String) {
    override def toString: String = s"${base}_$quote"
    def toSeq: Seq[String] = List(base, quote)
  }

  case class Trade(id: String, micros: Long, price: Double, size: Double) extends Timestamped

  case class TradeMD(source: String, topic: String, data: Trade) extends GenMD[Trade] {
    val dataType: String = "trades"
    def product: Pair = parseProductId(topic)
    override def micros: Long = data.micros
  }

  case class CurrencyConfig(name: Option[String],
                            alias: Option[String])

  trait Timestamped {
    def micros: Long
  }
  object Timestamped {
    val ordering: Ordering[Timestamped] = Ordering.by(_.micros)
  }

  case class PricePoint(price: Double, micros: Long) extends Timestamped

  sealed trait QuoteSide
  case object Bid extends QuoteSide
  case object Ask extends QuoteSide
}
