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
import core.DataSource.DataSourceConfig
import core.Exchange.ExchangeConfig
import core.Order._
import core.Utils.parseProductId
import exchanges.Simulator

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(dataDir: String,
                    strategyClassNames: Map[String, String],
                    dataSourceConfigs: Map[String, DataSourceConfig],
                    exchangeConfigs: Map[String, ExchangeConfig])
  extends PersistentActor with ActorLogging {

  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = Utils.buildMaterializer
  implicit val ec: ExecutionContext = system.dispatcher


  val dataSourceClassNames: Map[String, String] = dataSourceConfigs.mapValues(_.`class`)
//  dataSourceConfigs.mapValues(_.`class`) + ("internal" -> "sources.InternalDataSource")

  import TradingEngine._
  import DataSource._
  import scala.collection.immutable.Seq

  val snapshotInterval = 1000
  val defaultLatencyMicros: Long = 10 * 1000
  var state = EngineState()

  override def persistenceId: String = "trading-engine"

  /**
    * Turns an incoming command into a sequence of [[Event]] objects that affect the state in
    * some way and are then persisted, or into an [[EngineError]] to be returned to the sender.
    */
  def processCommand(command: Command): Either[EngineError, Seq[Event]] = command match {

    case StartTradingSession(
        strategyKey,
        strategyParams,
        mode,
        sessionEventsRef,
        initialBalances,
        (makerFeeOpt, takerFeeOpt)
    ) =>

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
        dataSourceAddresses = strategyOpt.get
          .initialize(strategyParams, dataSourceConfigs, initialBalances)
          .map(parseAddress)
      } catch {
        case err: Throwable =>
          return Left(EngineError(s"Strategy initialization error: $strategyKey", err))
      }

      // Initialization validation
      if (dataSourceAddresses.isEmpty) {
        return Left(EngineError(s"Strategy must declare at least one data source " +
          s"during initialization."))
      }

      var tickRefOpt: Option[ActorRef] = None

      // Validate and load requested data sources.
      // Also load exchanges instances while we're at it.
      var dataSources = Map.empty[String, DataSource]
      var exchanges = Map.empty[String, Exchange]
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

        if (exchangeConfigs.isDefinedAt(srcKey)) {
          val exClass = exchangeConfigs(srcKey).`class`
          var classOpt: Option[Class[_ <: Exchange]] = None
          try {
            classOpt = Some(getClass.getClassLoader
              .loadClass(exClass)
              .asSubclass(classOf[Exchange]))
          } catch {
            case err: ClassNotFoundException =>
              return Left(EngineError("Exchange class not found: " + exClass, err))
            case err: ClassCastException =>
              return Left(EngineError(s"Class $exClass must be a " +
                s"subclass of io.flashbook.core.Exchange", err))
          }

          try {
            val instance: Exchange = classOpt.get.getConstructor(classOf[Json])
              .newInstance(exchangeConfigs(srcKey).params)
            instance.setTickFn(() => {
              tickRefOpt.foreach(_ ! Tick(srcKey))
            })
            exchanges = exchanges + (srcKey -> (
              if (mode == Live) instance
              else new Simulator(instance, defaultLatencyMicros)))
          } catch {
            case err: Throwable =>
              return Left(EngineError(s"Exchange instantiation error: $srcKey", err))
          }
        }
      }

      val markets: Map[String, Set[Pair]] = dataSourceAddresses
          .filter {
            case Address(srcKey, topic, _) =>
              var parseError = false
              try {
                parseProductId(topic)
              } catch { case _ =>
                parseError = true
              }

              exchanges.contains(srcKey) && !parseError
          }
        .groupBy(_.srcKey)
        .mapValues(_.map(_.topic).map(parseProductId).toSet)

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
                         balances: Map[Account, Double] = initialBalances,
                         prices: Map[Market, Double] = Map.empty,
                         orderManagers: Map[String, OrderManager] =
                            markets.mapValues(_ => OrderManager()),

                         // Create action queue for every exchange
                         actionQueues: Map[String, ActionQueue] = markets
                           .mapValues(_ => ActionQueue()),

                         // Also an id manager per exchange
                         ids: Map[String, IdManager] = markets.mapValues(_ => IdManager()),
                         emittedReportEvents: Seq[ReportEvent] = Seq.empty)

        extends TradingSession {

        override val id: String = sessionId

        override def handleEvents(events: TradingSession.Event*): Unit =
          events.foreach(handleEvent)

        private var targets = Queue.empty[OrderTarget]
        private var reportEvents = Queue.empty[ReportEvent]

        def handleEvent(event: TradingSession.Event): Unit = {
          currentStrategySeqNr match {
            case Some(seq) if seq == seqNr =>
              event match {
                case target: OrderTarget =>
                  targets = targets.enqueue(target)

                case SessionReportEvent(event : GaugeEvent) =>
                  // Gauge event coming from a strategy, so we'll prefix the name with "local"
                  // to prevent naming conflicts.
                  reportEvents = reportEvents.enqueue(event.copy(key = "local." + event.key))

                case msg: LogMessage =>
                  // TODO: This should save to a time log, not log to stdout...
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

        def collectReportEvents: Seq[ReportEvent] = {
          val _events = reportEvents
          reportEvents = Queue.empty
          _events
        }
      }

      // Here, is where the fun begins.

      // Merge market data streams from the data sources we just loaded and stream the data into
      // the strategy instance. If this trading session is a backtest then we merge the data
      // streams by time. But if this is a live trading session then data is sent first come,
      // first serve to keep latencies low.
      val (tickRef, fut) = dataSourceAddresses
        .groupBy(_.srcKey)
        .flatMap { case (key, addresses) =>
          addresses.map {
            case Address(_, topic, dataType) =>
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
        .mergeMat(Source.actorRef[Tick](Int.MaxValue, OverflowStrategy.fail))(Keep.right)
        .map[Either[MarketData, Tick]] {
          case md: MarketData => Left(md)
          case tick: Tick => Right(tick)
        }
        .scan(Session()) { case (session @ Session(seqNr, balances, prices, oms,
            actions, ids, _), dataOrTick) =>

          implicit val ctx: TradingSession = session

          // Split up `dataOrTick` into two Options
          val (tick: Option[Tick], data: Option[MarketData]) = dataOrTick match {
            case Right(t: Tick) => (Some(t), None)
            case Left(md: MarketData) => (None, Some(md))
          }

          // Update the relevant exchange with the market data to collect fills and user data
          var fills: Option[scala.Seq[Fill]] = None
          var userData: Option[scala.Seq[OrderEvent]] = None
          if (data.exists(data => exchanges.isDefinedAt(data.source))) {
            exchanges(data.get.source).collect(session, data.get) match {
              case (_f, _d) =>
                fills = Some(_f)
                userData = Some(_d)
            }
          }

          // The user data is relayed to the strategy as StrategyEvents
          userData.foreach(_.foreach(strategy.handleEvent))

          // Use a sequence number to enforce the rule that Session.handleEvents is only
          // allowed to be called in the current call stack. Send market data to strategy,
          // then lock the handleEvent method again.
          data match {
            case Some(md) =>
              currentStrategySeqNr = Some(session.seqNr)
              strategy.handleData(md)(session)
              currentStrategySeqNr = None
            case None =>
          }

          // These should be empty if `handleData` wasn't called.
          val targets = session.collectTargets
          var newReportEvents = session.collectReportEvents

          var newIds = ids
          var newActions = actions
          var newBalances = balances
          var newPrices = prices
          var newOMs = oms

          def emitReportEvent(e: ReportEvent): Unit = {
            newReportEvents = newReportEvents :+ e
          }

          // If this data has price info attached, emit that price info.
          data match {
            case Some(pd: Priced) => // I love pattern matching on types.
              newPrices += (Market(pd.exchange, pd.product) -> pd.price)
              emitReportEvent(PriceEvent(pd.exchange, pd.product, pd.price, pd.micros))
            case _ =>
          }


          // Update ids and actions bookkeeping state in response to fills and user data emitted
          // from the relevant exchange.
          val ex = data.map(_.source).getOrElse(tick.get.exchange)
          def acc(currency: String) = Account(ex, currency)
          userData.get.foldLeft((newIds, newActions)) {
            /**
              * Either market or limit order received by the exchange. Associate the client id
              * with the exchange id. Do not close any actions yet. Wait until the order is
              * open, in the case of limit order, or done if it's a market order.
              */
            case ((is, as), OrderReceived(id, _, clientId, _)) =>
              (is.updated(ex, is(ex).receivedOrder(clientId.get, id)), as)

            /**
              * Limit order is opened on the exchange. Close the action that submitted it.
              */
            case ((is, as), OrderOpen(id, _, _, _, _)) =>
              (is, as.updated(ex, closeActionForOrderId(as(ex), is(ex), id)))

            /**
              * Either market or limit order is done. Could be due to a fill, or a cancel.
              * Disassociate the ids to keep memory bounded in the ids manager. Also close
              * the action for the order id.
              */
            case ((is, as), OrderDone(id, _, _, _, _, _)) =>
              (is.updated(ex, is(ex).orderComplete(id)),
                as.updated(ex, closeActionForOrderId(as(ex), is(ex), id)))

          } match { case (_newIds, _newActions) =>
              newIds = _newIds
              newActions = _newActions
          }

          newBalances = fills.get.foldLeft(newBalances) {
            case (bs, Fill(_, tradeId, fee, pair @ Pair(base, quote), price, size,
                createdAt, liquidity, side)) =>

              val ret = side match {
                /**
                  * If we just bought some BTC using USD, then the fee was already subtracted
                  * from the amount of available funds when determining the size of BTC filled.
                  * Simply add the filled size to the existing BTC balance for base. For quote,
                  * it's a little more complicated. We need to reconstruct the original amount
                  * of quote funds (total cost) that was used for the order.
                  *
                  * total_cost * (1 - fee) = size * price
                  */
                case Buy =>
                  // Cost without accounting for fees. This is what we pay the maker.
                  val rawCost = size * price

                  // Total quote currency paid out to both maker and exchange
                  val totalCost = rawCost / (1 - fee)

                  bs + (
                    acc(base) -> (bs.getOrElse(acc(base), 0.0) + size),
                    acc(quote) -> (bs(acc(quote)) - totalCost)
                  )

                /**
                  * If we just sold a certain amount of BTC for USD, then the fee is subtracted
                  * from the USD that is to be credited to our account balance.
                  */
                case Sell => bs + (
                  acc(base) -> (bs(acc(base)) - size),
                  acc(quote) -> (bs.getOrElse(acc(quote), 0.0) + size * price * (1 - fee))
                )
              }

              // Emit a trade event when we see a fill
              emitReportEvent(TradeEvent(tradeId, ex, pair.toString, createdAt, price, size))

              // Also balance info
              emitReportEvent(BalanceEvent(acc(base), ret(acc(base)), createdAt))
              emitReportEvent(BalanceEvent(acc(quote), ret(acc(quote)), createdAt))

              ret
          }

          // Send the order targets to the corresponding order manager.
          targets.foreach { target =>
            newOMs += (target.exchangeName ->
              newOMs(target.exchangeName).submitTarget(target))
          }

          // If necessary, expand the next target into actions and enqueue them for each
          // order manager.
          newOMs.foreach {
            case (exName, om) =>
              om.enqueueActions(
                exchanges(exName),
                newActions(exName),
                newBalances.filter(_._1.exchange == exName)
                  .map { case (acc, value) => (acc.currency, value) },
                newPrices.filter(_._1.exchange == exName)
                  .map { case (market, value) => (market.product, value) }
              ) match {
                case (_om, _actions) =>
                  newOMs += (exName -> _om)
                  newActions += (exName -> _actions)
              }
          }

          // Here is where we tell the exchanges to do stuff, like place or cancel orders.
          newActions.foreach { case (exName, acs) =>
            acs match {
              case ActionQueue(None, next +: rest) =>
                newActions += (exName -> ActionQueue(Some(next), rest))

                next match {
                  case PostMarketOrder(clientId, targetId, pair, side, size, funds) =>
                    newIds += (exName -> newIds(exName).initOrder(targetId, clientId))
                    exchanges(exName).order(MarketOrderRequest(clientId, side, pair, size, funds))

//                  case PostLimitOrder(clientId, targetId, pair, side, percent, price) =>
//                    newIds = newIds.updated(ex, newIds(ex).initOrder(targetId, clientId))
//                    exchanges(ex)
//                      .order(LimitOrderRequest(clientId, side, pair,
//                        BigDecimal(price).setScale(quoteIncr(pair.quote),
//                          if (side == Buy) BigDecimal.RoundingMode.CEILING
//                          else BigDecimal.RoundingMode.FLOOR).doubleValue,
//                        pPair(ex, pair).amount(if (side == Buy) Quote else Base, percent)
//                      ))

                  case CancelLimitOrder(id, targetId, pair) =>
                    exchanges(exName).cancel(ids(exName).actualIdForTargetId(targetId))
                }
              case _ =>
            }
          }

          session.copy(
            seqNr = seqNr + 1,
            balances = newBalances,
            prices = newPrices,
            orderManagers = newOMs,
            actionQueues = newActions,
            ids = newIds,
            emittedReportEvents = newReportEvents
          )
        }
        .drop(1)
        .toMat(Sink.foreach { s =>
          s.emittedReportEvents.foreach(sessionEventsRef ! _)
        })(Keep.both)
        .run

      // Allows exchanges to send ticks, so that we can react instantly to exchange events.
      tickRefOpt = Some(tickRef)

      fut.onComplete {
        case Success(_) =>
          println("success")
          sessionEventsRef ! PoisonPill
        case Failure(err) =>
          println("fail")
          sessionEventsRef ! PoisonPill
      }

      val startedEvent = SessionStarted(sessionId, strategyKey, strategyParams, mode,
        makerFeeOpt, takerFeeOpt)
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
      case BacktestQuery(strategyName, params, timeRange, balancesStr, makerFeeOpt, takerFeeOpt) =>

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

          val balances = parse(balancesStr).right.get
            .as[Map[String, Double]].right.get
            .map { case (key, v) =>
              val parts = key.split("/")
              (Account(parts(0), parts(1)), v)
            }

          val (ref: ActorRef, fut: Future[Report]) =
            Source.actorRef[ReportEvent](Int.MaxValue, OverflowStrategy.fail)
              .toMat(Sink.fold(emptyReport) {

                /**
                  * A trade occurred. Update the time and trades.
                  */
                case (report, event: TradeEvent) =>
                  report.copy(trades = report.trades :+ event)

                /**
                  * Gauge the gauge event. Gauge is one of those words that stops looking like a
                  * word the more you say it. Gauge. Try it ... gauge.
                  */
                case (report, event: GaugeEvent) =>
                  report.processGaugeEvent[GaugeEvent](event.key, event, _.value)

                /**
                  * A price was updated, process the related time series.
                  */
                case (report, event: PriceEvent) =>
                  report.processGaugeEvent[PriceEvent](
                    List("price", event.exchange, event.product.toString).mkString("."),
                    event, _.price)

                /**
                  * A balance was updated, process the related time series.
                  */
                case (report, event: BalanceEvent) =>
                  report.processGaugeEvent[BalanceEvent](
                    List("balance", event.account.exchange, event.account.currency).mkString("."),
                    event, _.balance)

              })(Keep.both)
              .run

          processCommand(StartTradingSession(strategyName, paramsJson,
            Backtest(timeRange), ref, balances, (makerFeeOpt, takerFeeOpt))) match {
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
                           timeRange: TimeRange,
                           balances: String,
                           makerFee: Option[Double],
                           takerFee: Option[Double]) extends Query

  sealed trait Response
  case object Pong extends Response {
    override def toString: String = "pong"
  }

  case class TimestampedPoint[T](micros: Long, point: T)

  case class TimeSeriesState(seriesStartMicros: Long,
                             barDuration: Duration,
                             currentBarEvents: Vector[Any],
                             currentValue: Option[Double],
                             pointValues: Vector[Double]) {

    def processEvent[T <: Timestamped](e: T,
                                       valFn: (Vector[Double], Vector[T]) => Double)
    : TimeSeriesState = {

      // First, backfill pointValues so that it has all bars until the bar that the current
      // event belongs to.
      val eventBarIndex = (e.micros - seriesStartMicros) / barDuration.toMicros
      var newState = this
      while (newState.pointValues.size < eventBarIndex) {
        val newPoints: Vector[Double] = newState.pointValues :+
          valFn(newState.pointValues, newState.currentBarEvents.map(_.asInstanceOf[T]))
        newState = newState.copy(
          currentBarEvents = Vector.empty[T],
          pointValues = newPoints
        )
      }

      // Then add the current event to the bar events vector
      val newEvents = newState.currentBarEvents :+ e
      newState.copy(
        currentBarEvents = newEvents,
        currentValue = Some(valFn(newState.pointValues, newEvents.map(_.asInstanceOf[T])))
      )
    }

    def values: Vector[Double] =
      if (currentValue.isDefined) pointValues :+ currentValue.get
      else pointValues
  }

  case class Report(strategy: String,
                    params: String,
                    timeRange: TimeRange,
                    done: Boolean,
                    trades: Vector[TradeEvent],
                    timeSeries: Map[String, TimeSeriesState])
    extends Response {

    // TODO: Taking the seriesStartMicros from timeRange.from makes an enormous prefix of empty
    // points in the time series when `from` is the default (0). What will we do about this?
    def processGaugeEvent[T <: Timestamped](name: String,
                                            event: T,
                                            valueFn: T => Double): Report = {
      val emptyTimeSeries =
        TimeSeriesState(timeRange.from, 1 seconds, Vector.empty[T], None, Vector.empty)
      copy(timeSeries = timeSeries + (name ->
        timeSeries.getOrElse(name, emptyTimeSeries).processEvent(event,
          (ps: Vector[Double], es: Vector[T]) => (ps, es) match {
            case (_, events) if events.nonEmpty => valueFn(events.last)
            case (points, _) if points.nonEmpty => points.last
            case (_, _) => 0
          })))
    }
  }

  sealed trait Command
  case class StartTradingSession(strategyKey: String,
                                 strategyParams: Json,
                                 mode: Mode,
                                 sessionEvents: ActorRef,
                                 initialBalances: Map[Account, Double],
                                 // Fee rates: (maker rate, taker rate)
                                 feeRates: (Option[Double], Option[Double])) extends Command

  sealed trait Event
  sealed trait PersistedEvent extends Event
  case class SessionStarted(id: String,
                            strategyKey: String,
                            strategyParams: Json,
                            mode: Mode,
                            makerFeeOpt: Option[Double],
                            takerFeeOpt: Option[Double]) extends PersistedEvent
  case class SessionFuture(fut: Future[akka.Done]) extends Event

  sealed trait ReportEvent extends Timestamped
  case class TradeEvent(id: String,
                        exchange: String,
                        product: String,
                        micros: Long,
                        price: Double,
                        size: Double) extends ReportEvent
  case class PriceEvent(exchange: String,
                        product: Pair,
                        price: Double,
                        micros: Long) extends ReportEvent

  case class BalanceEvent(account: Account,
                          balance: Double,
                          micros: Long) extends ReportEvent

  case class GaugeEvent(key: String, value: Double, micros: Long) extends ReportEvent

  final case class EngineError(private val message: String,
                               private val cause: Throwable = None.orNull)
    extends Exception(message, cause) with Response

  case class EngineState(sessions: Map[String, TradingSessionRecord] = Map.empty) {
    /**
      * A pure function that updates the state in response to an event that occurred in the
      * engine. No side effects please!
      */
    def update(event: Event): EngineState = event match {
      case SessionStarted(id, strategyKey, strategyParams, mode, makerFeeOpt, takerFeeOpt) =>
        copy(sessions = sessions + (id ->
          TradingSessionRecord(id, strategyKey, strategyParams, mode, makerFeeOpt, takerFeeOpt)))
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

//  case class PortfolioPair(p: Pair, amounts: (Double, Double)) {
//    case class CurrencyAmount(name: String, amount: Double)
//
//    def base: CurrencyAmount = CurrencyAmount(p.base, amounts._1)
//    def quote: CurrencyAmount = CurrencyAmount(p.quote, amounts._2)
//
//    def amount(role: PairRole, ratio: Double): Double = role match {
//      case Base => BigDecimal(base.amount * ratio)
//        .setScale(sizeIncr(base.name), BigDecimal.RoundingMode.FLOOR)
//        .toDouble
//      case Quote => BigDecimal(quote.amount * ratio)
//        .setScale(sizeIncr(quote.name), BigDecimal.RoundingMode.FLOOR)
//        .toDouble
//    }
//  }

  // TODO: These should really be configured or requested per-exchange
//  def quoteIncr(currency: String): Int = if (currency == "BTC") 5 else 2
//  def sizeIncr(currency: String): Int = if (currency == "USD") 2 else 6
}
