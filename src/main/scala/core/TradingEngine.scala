package core

import akka.actor.{ActorLogging, ActorRef, ActorSystem, PoisonPill, Status}
import akka.persistence._
import akka.pattern.pipe
import io.circe.Json
import io.circe.parser.parse
import java.util.UUID

import TradingSession._
import akka.NotUsed
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source}
import core.Action._
import core.Order.{Buy, Fill, Sell}
import exchanges.Simulator

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(dataDir: String,
                    strategyClassNames: Map[String, String],
                    dataSourceClassNames: Map[String, String],
                    exchangeClassNames: Map[String, String])
  extends PersistentActor with ActorLogging {

  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = Utils.buildMaterializer
  implicit val ec: ExecutionContext = system.dispatcher

  import TradingEngine._
  import DataSource._
  import scala.collection.immutable.Seq

  val snapshotInterval = 1000
  val defaultLatencyMicros = 1000
  var state = EngineState()

  override def persistenceId: String = "trading-engine"

  /**
    * Turns an incoming command into a sequence of [[Event]] objects that affect the state in
    * some way and are then persisted, or into an [[EngineError]] to be returned to the sender.
    */
  def processCommand(command: Command): Either[EngineError, Seq[Event]] = command match {

    case StartTradingSession(strategyKey, strategyParams, mode, sessionEventsRef) =>
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
      var loadedExchanges = Map.empty[String, Exchange]
      for (srcKey <- dataSourceAddresses.map(_.srcKey).toSet[String]) {
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
              return Left(EngineError("Exchange class not found: " +
                exchangeClassNames(srcKey), err))
            case err: ClassCastException =>
              return Left(EngineError(s"Class ${exchangeClassNames(srcKey)} must be a " +
                s"subclass of io.flashbook.core.Exchange", err))
          }

          try {
            loadedExchanges = loadedExchanges + (srcKey -> classOpt.get.newInstance)
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
                         actions: ActionQueue = ActionQueue(),
                         sessionEvents: Seq[SessionEvent] = Seq.empty) extends TradingSession {

        override val id: String = sessionId

        // Simulate exchanges unless we are in live trading mode
        override val exchanges: Map[String, Exchange] =
          if (mode == Live) loadedExchanges
          else loadedExchanges.mapValues(e => new Simulator(e, defaultLatencyMicros))

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
      dataSourceAddresses
        .groupBy(_.srcKey)
        .flatMap { case (key, addresses) =>
          addresses.map { case Address(_, topic, dataType) =>
            Source.fromIterator[MarketData](() =>
              dataSources(key).stream(dataDir, topic, dataType, mode match {
                case Backtest(range) => range
                case _ => TimeRange()
              }))
          }}
        .reduce[Source[MarketData, NotUsed]](mode match {
          case Backtest(range) => _.mergeSorted(_)
          case _ => _.merge(_)
        })
        .scan(Session()) { case (session @ Session(seqNr, balances, tm, ids, actions, _), data) =>
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
          var newSessionEvents = Vector(TradeEvent(Trade("foo", 1234, 56, 79)))

          // Update ids and actions bookkeeping state in response to fills and user data emitted
          // from the relevant exchange.
          session.exchanges.get(data.source) match {
            case Some(exchange) =>
              val (fills, userData) = exchange.update(session, data)
              def acc(currency: String) = Account(data.source, currency)

              userData.foldLeft((newIds, newActions)) {
                /**
                  * Either market or limit order received by the exchange. Associate the client id
                  * with the exchange id. Do not close any actions yet. Wait until the order is
                  * open, in the case of limit order, or done if it's a market order.
                  */
                case ((is, as), Received(id, _, clientId, _)) =>
                  (is.receivedOrder(clientId.get, id), as)

                /**
                  * Limit order is opened on the exchange. Close the action that submitted it.
                  */
                case ((is, as), Open(id, _, _, _, _)) =>
                  (is, closeActionForOrderId(as, is, id))

                /**
                  * Either market or limit order is done. Could be due to a fill, or a cancel.
                  * Disassociate the ids to keep memory bounded in the ids manager. Also close the
                  * action for the order id.
                  */
                case ((is, as), Done(id, _, _, _, _, _)) =>
                  (is.orderComplete(id), closeActionForOrderId(as, is, id))

              } match { case (_newIds, _newActions) =>
                  newIds = _newIds
                  newActions = _newActions
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
          targets.foldLeft((newActions, newTM)) {
            case ((as, ntm), target) =>
              tm.step(target) match { case (_tm, actionsSeq) => (as.enqueue(actionsSeq), _tm)}
          } match { case (_newActions, _newTM) =>
              newActions = _newActions
              newTM = _newTM
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
                case PostMarketOrder(clientId, targetId, pair, side, percent) =>
                  val exName = exchangeNameForTargetId(targetId)
                  newIds = newIds.initOrder(targetId, clientId)
                  session.exchanges(exName)
                    .order(MarketOrderRequest(clientId, side, pair,
                      if (side == Buy) None else Some(pPair(exName, pair).amount(Quote, percent)),
                      if (side == Sell) None else Some(pPair(exName, pair).amount(Base, percent))
                    ))

                case PostLimitOrder(clientId, targetId, pair, side, percent, price) =>
                  val exName = exchangeNameForTargetId(targetId)
                  newIds = newIds.initOrder(targetId, clientId)
                  session.exchanges(exName)
                    .order(LimitOrderRequest(clientId, side, pair,
                      BigDecimal(price).setScale(quoteIncr(pair.quote),
                        if (side == Buy) BigDecimal.RoundingMode.CEILING
                        else BigDecimal.RoundingMode.FLOOR).doubleValue,
                      pPair(exName, pair).amount(if (side == Buy) Quote else Base, percent)
                    ))

                case CancelLimitOrder(id, targetId, pair) =>
                  session.exchanges(exchangeNameForTargetId(targetId))
                    .cancel(ids.actualIdForTargetId(targetId))
              }
            case _ =>
          }

          session.copy(
            seqNr = seqNr + 1,
            balances = newBalances,
            targetManager = newTM,
            ids = newIds,
            actions = newActions,
            sessionEvents = newSessionEvents
          )
        }
        .drop(1)
        .runForeach { s =>
          s.sessionEvents.foreach(sessionEventsRef ! _)
        }
        .onComplete {
          case Success(_) =>
            println("success")
            sessionEventsRef ! PoisonPill
          case Failure(err) =>
            println("fail")
            sessionEventsRef ! PoisonPill
        }

      val startedEvent = SessionStarted(sessionId, strategyKey, strategyParams, mode)
      Right(startedEvent :: Nil)
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

    case query: Query => query match {
      case Ping => sender ! Pong

      /**
        * To resolve a backtest query, we start a trading session in Backtest mode and collect
        * all session events into a stream that we fold over to create a report.
        */
      case BacktestQuery(strategyName, params, timeRange) =>

        try {
          // TODO: Handle parse errors
          val paramsJson = parse(params).right.get
          val emptyReport = Report(
            strategyName,
            params,
            timeRange,
            done = true,
            Vector(),
            Map.empty
          )

          val (ref: ActorRef, fut: Future[Report]) =
            Source.actorRef[SessionEvent](Int.MaxValue, OverflowStrategy.fail)
              .toMat(Sink.fold(emptyReport) {
                case (memo, TradeEvent(t)) => memo.copy(
                  trades = memo.trades :+ t
                )
              })(Keep.both)
              .run

          processCommand(StartTradingSession(strategyName,
            paramsJson, Backtest(timeRange), ref)) match {
            case Left(err: EngineError) =>
              throw err
            case Right(events: Seq[Event]) =>
              events.foreach(println)
          }

          fut pipeTo sender
        } catch {
          case err: Throwable =>
            log.error("Uncaught error during backtesting", err)
            throw err
        }

      case _ => sender ! EngineError("Unsupported query type")
    }

    case cmd: Command =>
      processCommand(cmd) match {
        case Left(err) =>
          sender ! err
        case Right(events) =>
          persistAll(events.collect { case pe: PersistedEvent => pe }) { e =>
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

  sealed trait Query
  case object Ping extends Query
  case class BacktestQuery(strategyName: String,
                           params: String,
                           timeRange: TimeRange) extends Query

  sealed trait Response
  case object Pong extends Response {
    override def toString: String = "pong"
  }

  case class Report(strategy: String,
                    params: String,
                    timeRange: TimeRange,
                    done: Boolean,
                    trades: Vector[Trade],
                    balances: Map[String, Map[String, Vector[PricePoint]]]) extends Response

  sealed trait Command
  case class StartTradingSession(strategyKey: String,
                                 strategyParams: Json,
                                 mode: Mode,
                                 sessionEvents: ActorRef) extends Command

  sealed trait Event
  sealed trait PersistedEvent extends Event
  case class SessionStarted(id: String,
                            strategyKey: String,
                            strategyParams: Json,
                            mode: Mode) extends PersistedEvent
  case class SessionFuture(fut: Future[akka.Done]) extends Event

  sealed trait SessionEvent
  case class TradeEvent(trade: Trade) extends SessionEvent

  final case class EngineError(private val message: String,
                               private val cause: Throwable = None.orNull)
    extends Exception(message, cause) with Response

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

  def closeActionForOrderId(actions: ActionQueue, ids: IdManager, id: String): ActionQueue =
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
