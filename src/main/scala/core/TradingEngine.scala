package core

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.persistence._
import io.circe.Json
import java.util.UUID

import akka.stream.scaladsl.{Merge, MergeSorted, Sink, Source}
import TradingSession._
import akka.stream.OverflowStrategy

import scala.collection.immutable.Queue
import scala.concurrent.Future

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(strategyClassNames: Map[String, String],
                    dataSourceClassNames: Map[String, String],
                    exchangeClassNames: Map[String, String],
                    currencyConfigs: Map[String, CurrencyConfig])
  extends PersistentActor with ActorLogging {

  import TradingEngine._
  import DataSource._
  import scala.collection.immutable.Seq

  val snapshotInterval = 1000
  var state = EngineState()

  override def persistenceId: String = "trading-engine"

  /**
    * Turns an incoming command into a sequence of [[Event]] objects that affect the state in some
    * way and are then persisted, or into an [[EngineError]] to be returned to the sender.
    */
  def processCommand(command: Command): Either[EngineError, Seq[Event]] = command match {
    case StartTradingSession(strategyKey, strategyParams, mode) =>

      // Check that we have a config for the requested strategy.
      if (!strategyClassNames.isDefinedAt(strategyKey)) {
        return Left(EngineError(s"Unknown strategy: $strategyKey"))
      }

      // Load and validate the strategy class
      var strategyClassOpt: Option[Class[_ <: Strategy]] = None
      var strategyOpt: Option[Strategy] = None
      try {
        strategyClassOpt = Some(getClass.getClassLoader
          .loadClass(strategyClassNames(strategyKey))
          .asSubclass(classOf[Strategy]))
      } catch {
        case err: ClassNotFoundException =>
          return Left(
            EngineError(s"Strategy class not found: ${strategyClassNames(strategyKey)}", err))

        case err: ClassCastException =>
          return Left(EngineError(s"Class ${strategyClassNames(strategyKey)} must be a " +
            s"subclass of io.flashbook.core.Strategy", err))
      }

      // Instantiate the strategy
      try {
        strategyOpt = Some(strategyClassOpt.get.newInstance)
      } catch {
        case err: Throwable =>
          return Left(EngineError(s"Strategy instantiation error: $strategyKey", err))
      }

      // Initialize the strategy and collect the data source addresses it returns
      var dataSourceAddresses = Seq.empty[Address]
      try {
        dataSourceAddresses = strategyOpt.get.initialize(strategyParams).map(parseAddress)
      } catch {
        case err: Throwable =>
          return Left(EngineError(s"Strategy initialization error: $strategyKey", err))
      }

      // Initialization validation
      if (dataSourceAddresses.isEmpty) {
        return Left(EngineError(s"Strategy must declare at least one data source " +
          s"during initialization."))
      }

      // Validate and load requested data sources.
      // Also load exchanges instances while we're at it.
      var dataSources = Map.empty[String, DataSource]
      var _exchanges = Map.empty[String, Exchange]
      for (srcKey <- dataSourceAddresses.map(_.srcKey).toSet) {
        if (!dataSourceClassNames.isDefinedAt(srcKey)) {
          return Left(EngineError(s"Unknown data source: $srcKey"))
        }

        var dataSourceClassOpt: Option[Class[_ <: DataSource]] = None
        try {
          dataSourceClassOpt = Some(getClass.getClassLoader
            .loadClass(dataSourceClassNames(srcKey))
            .asSubclass(classOf[DataSource]))
        } catch {
          case err: ClassNotFoundException =>
            return Left(EngineError(s"Data source class not found: " +
              s"${dataSourceClassNames(srcKey)}", err))
          case err: ClassCastException =>
            return Left(EngineError(s"Class ${dataSourceClassNames(srcKey)} must be a " +
              s"subclass of io.flashbook.core.DataSource", err))
        }

        try {
          dataSources = dataSources + (srcKey -> dataSourceClassOpt.get.newInstance)
        } catch {
          case err: Throwable =>
            return Left(EngineError(s"Data source instantiation error: $srcKey", err))
        }

        if (exchangeClassNames.isDefinedAt(srcKey)) {
          var classOpt: Option[Class[_ <: Exchange]] = None
          try {
            classOpt = Some(getClass.getClassLoader
              .loadClass(exchangeClassNames(srcKey))
              .asSubclass(classOf[Exchange]))
          } catch {
            case err: ClassNotFoundException =>
              return Left(EngineError("Strategy class not found: " +
                exchangeClassNames(srcKey), err))
            case err: ClassCastException =>
              return Left(EngineError(s"Class ${exchangeClassNames(srcKey)} must be a " +
                s"subclass of io.flashbook.core.Exchange", err))
          }

          try {
            _exchanges = _exchanges + (srcKey -> classOpt.get.newInstance)
          } catch {
            case err: Throwable =>
              return Left(EngineError(s"Exchange instantiation error: $srcKey", err))
          }
        }
      }

      // Here, is where the fun begins.

      // Keep track of currencies, inferred from transaction requests.
      var currencies = Map.empty[String, CurrencyConfig]
      def declareCurrency(currency: String): Unit = {
        currencies += (currency -> currencyConfigs(currency))
      }

      // Initialize portfolio of accounts. When backtesting, we just use the initial balances
      // that were supplied to the session. When live trading we request balances from each
      // account via the exchange's API.

      val strategy = strategyOpt.get
      val sessionId = UUID.randomUUID.toString
      var currentStrategySeqNr: Option[Long] = None
      val sessionActor = context.actorOf(Props[SessionActor], "session")
      val targetManager = new TargetManager

      /**
        * The trading session that we fold market data over. We pass the running session instance
        * to the strategy every time we call `handleData`.
        */
      case class Session(state: SessionState = SessionState()) extends TradingSession {
        override val id: String = sessionId
        override val exchanges: Map[String, Exchange] = _exchanges

        override def handleEvents(events: TradingSession.Event*): Unit =
          events.foreach(handleEvent)

        def handleEvent(event: TradingSession.Event): Unit = {
          currentStrategySeqNr match {
            case Some(seq) if seq == state.seqNr =>
              event match {
                case target: OrderTarget =>
                  targetManager.step(target).foreach(sessionActor ! _)
                case msg: LogMessage =>
                  // TODO: This should save to a queue, not log to stdout...
                  log.info(msg.message)
              }

            case _ =>
              throw new RuntimeException("Asynchronous strategies are not supported. " +
                "`handleEvent` must be called in the same call stack that handleData " +
                "was called.")
          }
        }

        def setState(fn: SessionState => SessionState): Session = copy(state = fn(state))
      }


      /**
        * SessionActor processes market data as it comes in, as well as actions as they are
        * emitted from strategies. Both of these data streams interact with session state, which
        * this actor is a container for.
        */
      class SessionActor extends Actor {
        var session: Session = Session()

        override def receive: Receive = {
          case action: Action => action match {
            case orderAction: OrderAction => orderAction match {
              case PostMarketOrder(id, targetId, pair, side, percent) =>
              case PostLimitOrder(id, targetId, pair, side, percent, price) =>
              case CancelLimitOrder(id, targetId, pair) =>
            }
          }

          case data: MarketData =>
            // Use a sequence number to enforce the rule that Session.handleEvent is only allowed
            // to be called in the current call stack.
            currentStrategySeqNr = Some(session.state.seqNr)

            // Send market data to strategy
            strategy.handleData(data)(session)

            // Lock the handleEvent method
            currentStrategySeqNr = None

            session = session.setState(state => state.copy(
              seqNr = state.seqNr + 1
            ))
        }
      }

      // Merge market data streams from the data sources we just loaded and stream the data into
      // the strategy instance. If this trading session is a backtest then we merge the data
      // streams by time. But if this is a live trading session then data is sent first come,
      // first serve to keep latencies low.
      dataSourceAddresses
        .groupBy(_.srcKey)
        .flatMap { case (key, addresses) =>
          addresses.map { case Address(_, topic, dataType) =>
            dataSources(key).stream(topic, dataType, mode match {
              case Backtest(range) => range
              case _ => TimeRange()
            })
          }}
        .reduce(mode match {
          case Backtest(range) => _.mergeSorted(_)
          case _ => _.merge(_)
        })
        .runForeach(sessionActor ! _)

      Right(List(SessionStarted(sessionId, strategyKey, strategyParams, mode)))
  }

  /**
    * Persist every received command and occasionally save state snapshots so that the event log
    * doesn't grow out of bounds.
    */
  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess(SnapshotMetadata(_, seqNr, _)) =>
      log.info("Snapshot saved: {}", seqNr)
      deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = seqNr - 1))

    case SaveSnapshotFailure(SnapshotMetadata(_, seqNr, _), cause) =>
      log.error(cause, "Failed to save snapshots: {}", seqNr)

    case DeleteSnapshotsSuccess(SnapshotSelectionCriteria(maxSequenceNr, _, _, _)) =>
      log.info("Snapshot deleted: {}", maxSequenceNr)
      deleteMessages(maxSequenceNr + 1)

    case DeleteSnapshotsFailure(SnapshotSelectionCriteria(maxSequenceNr, _, _, _), cause) =>
      log.error(cause, "Failed to delete snapshots: {}", maxSequenceNr)

    case DeleteMessagesSuccess(toSeqNr) =>
      log.info("Events deleted: {}", toSeqNr)

    case DeleteMessagesFailure(cause, toSeqNr) =>
      log.error(cause, "Failed to delete events: {}", toSeqNr)

    case cmd: Command =>
      processCommand(cmd) match {
        case Left(err) =>
          sender ! err
        case Right(events) =>
          persistAll(events) { e =>
            state = state.update(e)
            if (lastSequenceNr % snapshotInterval == 0) {
              saveSnapshot(state)
            }
          }
      }
  }

  /**
    * Recover persisted state after a restart or crash.
    */
  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot: EngineState) =>
      state = snapshot
    case RecoveryCompleted => // ignore
    case event: Event =>
      state = state.update(event)
  }
}

object TradingEngine {
  trait Command
  case class StartTradingSession(strategyKey: String,
                                 strategyParams: Json,
                                 mode: Mode) extends Command
  trait Event
  case class SessionStarted(id: String,
                            strategyKey: String,
                            strategyParams: Json,
                            mode: Mode) extends Event

  final case class EngineError(private val message: String,
                                 private val cause: Throwable = None.orNull)
    extends Exception(message, cause)

  case class EngineState(sessions: Map[String, TradingSession] = Map.empty) {
    /**
      * A pure function that updates the state in response to an event that occurred in the
      * engine. No side effects please!
      */
    def update(event: Event): EngineState = ???
  }

  case class ActionQueue(active: Option[Action], queue: Queue[Action]) {
    def enqueue(action: Action): ActionQueue = copy(queue = queue.enqueue(action))
    def closeActive: ActionQueue = active match {
      case Some(_) => copy(active = None)
    }
  }
  object ActionQueue {
    def empty: ActionQueue = ActionQueue(None, Queue.empty)
  }
}
