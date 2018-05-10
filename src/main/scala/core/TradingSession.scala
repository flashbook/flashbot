package core

object TradingSession {
  trait Event

  case class SessionState(seqNr: Long = 0)
}

/**
  * TradingSession contains an instance of a running strategy and processes the events emitted
  * by it.
  */
trait TradingSession {
  import TradingSession._

  def id: String
  def timeRange: TimeRange
  def state: SessionState
  def handleEvent(event: Event): Unit
}
//abstract case class TradingSession(timeRange: TimeRange, state: TradingSession.SessionState) {
//  def handleEvent(event: TradingSession.Event)
//}
