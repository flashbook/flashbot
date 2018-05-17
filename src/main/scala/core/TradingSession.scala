package core

object TradingSession {
  trait Event
  case class LogMessage(message: String) extends Event
  case class OrderTarget(exchangeName: String, ratio: Ratio, pair: Pair,
                         price: Option[(String, Double)]) extends Event {
    def id: String = (exchangeName :: pair ::
      price.map(_._1).map(List(_)).getOrElse(List.empty)).mkString(":")
  }

  def exchangeNameForTargetId(id: String): String = id.split(":").head

  trait Mode
  case class Backtest(range: TimeRange) extends Mode
  case object Paper extends Mode
  case object Live extends Mode
}

trait TradingSession {
  import TradingSession._

  def id: String
  def exchanges: Map[String, Exchange]

  def handleEvents(events: Event*): Unit
}
