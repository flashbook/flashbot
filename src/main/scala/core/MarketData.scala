package core

/**
  * Any kind of data that can be streamed into strategies.
  */
trait MarketData {
  /**
    * The time in nanos that the event occurred.
    */
  def time: Long

  /**
    * Name of the data source of this item.
    */
  def source: String

  /**
    * The topic that identifies the market data stream under the specified data source.
    */
  def topic: String

  /**
    * The string representation of the type of data being streamed.
    */
  def dataType: String
}

object MarketData {
  implicit val ordering: Ordering[MarketData] = Ordering.by(_.time)

  // Generic market data container
  trait GenMD[T] extends MarketData {
    def data: T
  }

  abstract class PricedMD[T](val time: Long,
                             val source: String,
                             val topic: String,
                             val dataType: String,
                             val price: Double,
                             val data: T) extends GenMD[T] with Priced {
    def product: Pair = Utils.parseProductId(topic)
  }

  trait Sequenced {
    def seq: Long
  }

  trait HasProduct {
    def product: Pair
  }

  trait Priced extends HasProduct {
    def price: Double
  }
}
