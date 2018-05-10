package core

/**
  * Any kind of data that can be streamed into strategies.
  */
trait MarketData {
  /**
    * The time in nanos that the event occurred.
    */
  def timestamp: Long

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
}
