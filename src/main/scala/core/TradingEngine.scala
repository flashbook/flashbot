package core

import akka.actor.ActorLogging
import akka.persistence._
import io.circe.Json
import java.util.UUID

import TradingSession._
import core.Action._
import core.Order.{Buy, Fill, Sell}

import scala.collection.immutable.Queue

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(strategyClassNames: Map[String, String],
                    dataSourceClassNames: Map[String, String],
                    exchangeClassNames: Map[String, String])
  extends PersistentActor with ActorLogging {

  import TradingEngine._
  import DataSource._
  import scala.collection.immutable.Seq

  val snapshotInterval = 1000
  var state = EngineState()

  override def persistenceId: String = "trading-engine"

  /**
    * Turns an incoming command into a sequence of [[Event]] objects that affect the state in
    * some way and are then persisted, or into an [[EngineError]] to be returned to the sender.
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

      // Keep track of currencies, inferred from transaction requests.
      var currencies = Map.empty[String, CurrencyConfig]

      // Initialize portfolio of accounts. When backtesting, we just use the initial balances
      // that were supplied to the session. When live trading we request balances from each
      // account via the exchange's API.

      val strategy = strategyOpt.get
      val sessionId = UUID.randomUUID.toString
      var currentStrategySeqNr: Option[Long] = None

      /**
        * The trading session that we fold market data over. We pass the running session instance
        * to the strategy every time we call `handleData`.
        */
      case class Session(seqNr: Long = 0,
                         balances: Map[Account, Double] = Map.empty,
                         targetManager: TargetManager = TargetManager(),
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

      // Here, is where the fun begins.

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
        .scan(Session()) { case (session @ Session(seqNr, balances, tm, ids, actions), data) =>
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
          var newTM = tm

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

              // Lorde, Lorde, Lorde .... I am Lorde
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
          (newActions, newTM) = targets.foldLeft((newActions, newTM)) {
            case ((as, ntm), target) =>
              tm.step(target) match { case (_tm, actionsSeq) => (as.enqueue(actionsSeq), _tm)}
          }

          def pPair(exName: String, pair: Pair): PortfolioPair =
            PortfolioPair(pair, (newBalances(Account(exName, pair.base)),
              newBalances(Account(exName, pair.quote))),
              if (pair.quote == "BTC") 0.00001 else 0.01)

          // Here is where we tell the exchange to do stuff, like place or cancel orders.
          newActions match {
            case ActionQueue(None, next +: rest) =>
              newActions = ActionQueue(Some(next), rest)
              next match {
                case PostMarketOrder(id, targetId, pair, side, percent) =>
                  val exName = exchangeNameForTargetId(targetId)
                  newIds = newIds.initOrder(targetId, id)
                  session.exchanges(exName)
                    .order(MarketOrderRequest(id, side, pair,
                      if (side == Buy) None else Some(pPair(exName, pair).amount(Quote, percent)),
                      if (side == Sell) None else Some(pPair(exName, pair).amount(Base, percent))
                    ))

                case PostLimitOrder(id, targetId, pair, side, percent, price) =>
                  val exName = exchangeNameForTargetId(targetId)
                  newIds = newIds.initOrder(targetId, id)
                  session.exchanges(exName)
                    .order(LimitOrderRequest(id, side, pair,
                      BigDecimal(price).setScale(quoteIncr(pair.quote),
                        if (side == Buy) BigDecimal.RoundingMode.CEILING
                        else BigDecimal.RoundingMode.FLOOR).doubleValue,
                      pPair(exName, pair).amount(if (side == Buy) Quote else Base, percent)
                    ))

                case CancelLimitOrder(id, targetId, pair) =>
                  session.exchanges(exchangeNameForTargetId(targetId))
                    .cancel(ids.actualIdForTargetId(targetId))
              }
          }

          session.copy(
            seqNr = seqNr + 1,
            balances = newBalances,
            targetManager = newTM,
            ids = newIds,
            actions = newActions
          )
        }
        .runForeach(println)

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

  sealed trait Event
  case class SessionStarted(id: String,
                            strategyKey: String,
                            strategyParams: Json,
                            mode: Mode) extends Event

  final case class EngineError(private val message: String,
                               private val cause: Throwable = None.orNull)
    extends Exception(message, cause)

  case class EngineState(sessions: Map[String, TradingSessionRecord] = Map.empty) {
    /**
      * A pure function that updates the state in response to an event that occurred in the
      * engine. No side effects please!
      */
    def update(event: Event): EngineState = event match {
      case SessionStarted(id, strategyKey, strategyParams, mode) =>
        copy(sessions = sessions + (id ->
          TradingSessionRecord(id, strategyKey, strategyParams, mode)))
    }
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

  case class PortfolioPair(p: Pair, amounts: (Double, Double), incr: Double) {
    case class CurrencyAmount(name: String, amount: Double)
    def base: CurrencyAmount = CurrencyAmount(p.base, amounts._1)
    def quote: CurrencyAmount = CurrencyAmount(p.quote, amounts._2)

    def amount(role: PairRole, ratio: Double): Double = role match {
      case Base => BigDecimal(base.amount * ratio)
        .setScale(sizeIncr(base.name), BigDecimal.RoundingMode.FLOOR)
        .toDouble
      case Quote => BigDecimal(quote.amount * ratio)
        .setScale(sizeIncr(quote.name), BigDecimal.RoundingMode.FLOOR)
        .toDouble
    }
  }

  def quoteIncr(currency: String): Int = if (currency == "BTC") 5 else 2
  def sizeIncr(currency: String): Int = if (currency == "USD") 2 else 6

}
