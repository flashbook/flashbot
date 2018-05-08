package core

object TradingSession {
  trait Event
  case class RegisterDataSources(ids: Seq[String]) extends Event
}

/**
  * TradingSession contains an instance of a running strategy and processes the events emitted
  * by it.
  */
abstract case class TradingSession(from: Long, to: Long) {
  import TradingSession._

  def handleEvent(event: Event)
}
