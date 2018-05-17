package core

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.persistence._
import io.circe.Json
import java.util.UUID

import TradingSession._
import core.Action._
import core.Order.{Buy, Fill, Maker, Sell}

import scala.collection.immutable.Queue
import scala.concurrent.Future
import scala.util.{Failure, Success}

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
      val targetManager = new TargetManager

      /**
        * The trading session that we fold market data over. We pass the running session instance
        * to the strategy every time we call `handleData`.
        */
      case class Session(seqNr: Long = 0,
//                         orders: Map[String, Map[Pair, Order]] = Map.empty,
                         balances: Map[Account, Double] = Map.empty,
                         ids: IdManager = IdManager(),
                         actions: ActionQueue = ActionQueue()) extends TradingSession {

        override val id: String = sessionId
        override val exchanges: Map[String, Exchange] = _exchanges

        override def handleEvents(events: TradingSession.Event*): Unit =
          events.foreach(handleEvent)

        private var targets = Queue.empty[OrderTarget]

        def handleEvent(event: TradingSession.Event): Unit = {
          currentStrategySeqNr match {
            case Some(seq) if seq == seqNr =>
              event match {
                case target: OrderTarget =>
                  targets = targets.enqueue(target)
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

        def collectTargets: Seq[OrderTarget] = {
          val _targets = targets
          targets = Queue.empty
          _targets
        }
      }

      // Merge market data streams from the data sources we just loaded and stream the data into
      // the strategy instance. If this trading session is a backtest then we merge the data
      // streams by time. But if this is a live trading session then data is sent first come,
      // first serve to keep latencies low.
      val done = dataSourceAddresses
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
        .scan(Session()) { case (session @ Session(_, balances, ids, actions), data) =>
          // Use a sequence number to enforce the rule that Session.handleEvents is only
          // allowed to be called in the current call stack. Send market data to strategy,
          // then lock the handleEvent method again.
          currentStrategySeqNr = Some(session.seqNr)
          strategy.handleData(data)(session)
          currentStrategySeqNr = None

          val targets = session.collectTargets
          var newIds = ids
          var newActions = actions
          var newBalances = balances

          // Update ids and actions bookkeeping state in response to fills and user data emitted
          // from the relevant exchange.
          session.exchanges.get(data.source) match {
            case Some(exchange) =>
              val (fills, userData) = exchange.update(session)
              def acc(currency: String) = Account(data.source, currency)

              (newIds, newActions) = userData.foldLeft((newIds, newActions)) {
                case ((is, as), Received(id, _, clientId, _)) =>
                  (is.receivedOrder(clientId.get, id), as)
                case ((is, as), Open(id, _, _, _, _)) =>
                  (is, closeTxForOrderId(as, is, id))
                case ((is, as), Done(id, p, _, reason, _, _)) =>
                  (is.orderComplete(id), reason match {
                    case Canceled => closeTxForOrderId(as, is, id)
                    case Filled => closeTxForOrderId(as, is, id)
                    case _ => as
                  })
              }

              newBalances = fills.foldLeft(newBalances) {
                case (bs, Fill(_, _, fee, Pair(base, quote), price, size, _, _, Buy)) =>
                  bs + (
                    acc(base) -> (bs(acc(base)) + size),
                    acc(quote) -> (bs(acc(quote)) - (price * size * (1 + fee)))
                  )
                case (bs, Fill(_, _, fee, Pair(base, quote), price, size, _, _, Sell)) =>
                  bs + (
                    acc(base) -> (bs(acc(base)) - size),
                    acc(quote) -> (bs(acc(quote)) + (price * size * (1 - fee)))
                  )
              }

            case _ =>
          }

          // Send the order targets to the target manager and enqueue the actions emitted by it.
          targets.foreach { target =>
            newActions = newActions.enqueue(targetManager.step(target))
          }

          session.copy(
            seqNr = session.seqNr + 1,
            ids = newIds,
            actions = newActions,
            balances = newBalances
          )
        }
        .runForeach(println)
//        .map { case (session @ Session(_, _, balances, ids, data), targets) =>
//          session.exchange match {
//            case Some(exchange) =>
//              val (fills, userEvents) = exchange.update(session)
//            case _ =>
//          }
//          session
//        }
//        .runReduce((a, b) => b)

      Right(List(SessionStarted(sessionId, strategyKey, strategyParams, mode)))

      /**
        * SessionActor processes market data as it comes in, as well as actions as they are
        * emitted from strategies. Both of these data streams interact with session state, which
        * this actor is a container for.
        */
//      class SessionActor extends Actor {
//        import OrderManager._
//
//        var session: Session = Session()
//        // TODO: All actions per strategy are executed sequentially. We may want to allow
//        // concurrent actions if they are from different exchanges. May be helpful for
//        // inter-exchange strategies where latency matters.
//        var actions: ActionQueue = ActionQueue()
//
//        override def receive: Receive = {
//          case action: OrderAction =>
//            actions = actions.enqueue(action)
//            touch()
//
//          case data: MarketData =>
//
//            // Process user data emitted from the OrderManager.
//            val newSession = oms.emitUserData(data).foldLeft(session) {
//              case (memo, UserTx(TradeTx(id, exchange, time, makerId, takerId, price, size))) =>
//              case (memo, UserOrderEvent(event)) => event match {
//                case OrderOpen(exchange, Order(id, pair, side, amount, price)) =>
//                  memo.copy(
//                    balances = memo.balances,
//                    orders = memo.orders
//                  )
//                case OrderCanceled(id, exchange) =>
//                  memo.copy(orders = memo.orders - id)
//              }
//            }
//
//            // Use a sequence number to enforce the rule that Session.handleEvents is only
//            // allowed to be called in the current call stack.
//            currentStrategySeqNr = Some(newSession.seqNr)
//
//            // Send market data to strategy
//            strategy.handleData(data)(newSession)
//
//            // Lock the handleEvent method
//            currentStrategySeqNr = None
//
//            // Incoming market data is the only thing that increments the session sequence number.
//            session = newSession.copy(
//              seqNr = session.seqNr + 1
//            )
//
//          case rsp: ActionResponse => rsp match {
//            case Ok =>
//              actions = actions.closeActive
//              touch()
//            case Fail =>
//              // TODO: This error should stop the show. Does it? I don't think it does currently.
//              throw EngineError("Action error")
//          }
//        }
//
//        // Keep the actions flowing
//        def touch(): Unit = {
//          actions match {
//            case ActionQueue(None, (next: OrderAction) +: rest) =>
//              // Execute the action via the OMS.
//              oms.submitAction(next) onComplete {
//                case Success(rsp) =>
//                  self ! rsp
//                case Failure(err) =>
//                  // System/network errors with the actual request will surface here.
//                  // TODO: Same as above, make sure this exits the session.
//                  throw EngineError("Network error", err)
//              }
//
//              // If there's an action queued up and no active action, activate the next one.
//              actions = ActionQueue(Some(next), rest)
//
//            case _ =>
//              // Otherwise, nothing to do here.
//          }
//        }
//      }
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

  // Manages the relationships of the three types of order ids in our system:
  // Actual ids, client ids, target ids.
  case class IdManager(clientToTarget: Map[String, String] = Map.empty,
                       targetToActual: Map[String, String] = Map.empty,
                       actualToTarget: Map[String, String] = Map.empty) {

    def initOrder(targetId: String, clientId: String): IdManager =
      copy(clientToTarget = clientToTarget + (clientId -> targetId))

    def receivedOrder(clientId: String, actualId: String): IdManager = copy(
      targetToActual = targetToActual + (clientToTarget(clientId) -> actualId),
      actualToTarget = actualToTarget + (actualId -> clientToTarget(clientId)),
      clientToTarget = clientToTarget - clientId
    )

    def orderComplete(actualId: String): IdManager = copy(
      targetToActual = targetToActual - actualToTarget(actualId),
      actualToTarget = actualToTarget - actualId
    )

    def actualIdForTargetId(targetId: String): String = targetToActual(targetId)
  }


  def closeTxForOrderId(actions: ActionQueue, ids: IdManager, id: String): ActionQueue =
    actions match {
      case ActionQueue(Some(action), _) if ids.actualIdForTargetId(action.targetId) == id =>
        actions.closeActive
      case _ => actions
    }
}
