package core

import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, and order execution.
  */
class TradingEngine extends PersistentActor {
  import TradingEngine._

  val snapshotInterval = 1000
  var state = EngineState()

  override def persistenceId: String = "trading-engine"

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot: EngineState) =>
      state = snapshot
    case RecoveryCompleted => // ignore
    case event =>
  }

  override def receiveCommand: Receive = {
    case cmd: Command =>
      if (lastSequenceNr % snapshotInterval == 0) {
        saveSnapshot(state)
      }
  }
}

object TradingEngine {
  trait Command
  case class LoadBot() extends Command

  case class EngineState()
}
