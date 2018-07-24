package core

import java.util.{Date, UUID}
import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill}
import akka.stream.{ActorAttributes, ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source}
import core.Action.{ActionQueue, CancelLimitOrder, PostMarketOrder}
import core.DataSource.{Address, DataSourceConfig}
import core.Exchange.ExchangeConfig
import core.Order.{Buy, Fill, Sell}
import core.Report._
import core.TradingEngine._
import core.Utils.parseProductId
import exchanges.Simulator
import io.circe.Json

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object TradingSession {
  trait Event
  case class LogMessage(message: String) extends Event
  case class OrderTarget(exchangeName: String, ratio: Double, pair: Pair,
                         price: Option[(String, Double)]) extends Event {
    def id: String = (exchangeName :: pair ::
      price.map(_._1).map(List(_)).getOrElse(List.empty)).mkString(":")
  }
  case class SessionReportEvent(event: ReportEvent) extends Event

  def exchangeNameForTargetId(id: String): String = id.split(":").head

  sealed trait Mode
  case class Backtest(range: TimeRange) extends Mode
  case object Paper extends Mode
  case object Live extends Mode

  object Mode {
    def apply(str: String): Mode = str match {
      case "live" => Live
      case "paper" => Paper
    }
  }

  case class TradingSessionState(id: String,
                                 strategy: String,
                                 strategyParams: Json,
                                 mode: Mode,
                                 startedAt: Long,
                                 balances: Map[Account, Double],
                                 report: Report) {
    def updateReport(delta: ReportDelta): TradingSessionState = copy(report = report.update(delta))
  }

  case class SessionSetup(markets: Map[String, Set[Pair]],
                          dataSourceAddresses: Seq[Address],
                          dataSources: Map[String, DataSource],
                          exchanges: Map[String, Exchange],
                          strategy: Strategy,
                          sessionId: String,
                          sessionMicros: Long)

  class SessionActor(dataDir: String,
                     strategyClassNames: Map[String, String],
                     dataSourceConfigs: Map[String, DataSourceConfig],
                     exchangeConfigs: Map[String, ExchangeConfig],
                     strategyKey: String,
                     strategyParams: Json,
                     mode: Mode,
                     sessionEventsRef: ActorRef,
                     initialBalances: Map[Account, Double]) extends Actor with ActorLogging {

    implicit val ec: ExecutionContext = context.dispatcher
    implicit val system: ActorSystem = context.system
    implicit val mat: ActorMaterializer = Utils.buildMaterializer

    val dataSourceClassNames: Map[String, String] = dataSourceConfigs.mapValues(_.`class`)
    val defaultLatencyMicros: Long = 10 * 1000

    var tickRefOpt: Option[ActorRef] = None

    def setup(): Either[EngineError, SessionSetup] = {

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

      var dataSources = Map.empty[String, DataSource]
      def loadDataSource(srcKey: String): DataSource = {
        // If it is already loaded, do nothing.
        if (dataSources.contains(srcKey)) {
          return dataSources(srcKey)
        }

        // Check that we can find its class name
        if (!dataSourceClassNames.isDefinedAt(srcKey)) {
          throw EngineError(s"Unknown data source: $srcKey")
        }

        var dataSourceClassOpt: Option[Class[_ <: DataSource]] = None
        try {
          dataSourceClassOpt = Some(getClass.getClassLoader
            .loadClass(dataSourceClassNames(srcKey))
            .asSubclass(classOf[DataSource]))
        } catch {
          case err: ClassNotFoundException =>
            throw EngineError(s"Data source class not found: " +
              s"${dataSourceClassNames(srcKey)}", err)
          case err: ClassCastException =>
            throw EngineError(s"Class ${dataSourceClassNames(srcKey)} must be a " +
              s"subclass of io.flashbook.core.DataSource", err)
        }

        try {
          val dataSource = dataSourceClassOpt.get.newInstance
          dataSources = dataSources + (srcKey -> dataSource)
          dataSource
        } catch {
          case err: Throwable =>
            throw EngineError(s"Data source instantiation error: $srcKey", err)
        }
      }

      // Initialize the strategy and collect the data source addresses it returns
      var dataSourceAddresses = Seq.empty[Address]
      try {
        val expandedDataSourceConfigs = dataSourceConfigs.map {
          case (k, c) => (k, c.copy(topics = c.topics.flatMap {
            case ("*", value: Json) => loadDataSource(k).index.toSeq.map((_, value))
            case kv: (String, Json) => Seq(kv)
          }))
        }
        dataSourceAddresses = strategyOpt.get
          .initialize(strategyParams, expandedDataSourceConfigs, initialBalances)
          .map(DataSource.parseAddress)
      } catch {
        case err: EngineError =>
          return Left(err)
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
      var exchanges = Map.empty[String, Exchange]
      for (srcKey <- dataSourceAddresses.map(_.srcKey).toSet[String]) {
        try {
          loadDataSource(srcKey)
        } catch {
          case err: EngineError =>
            return Left(err)
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
            val instance: Exchange = classOpt.get.getConstructor(
              classOf[Json], classOf[ActorSystem], classOf[ActorMaterializer])
              .newInstance(exchangeConfigs(srcKey).params, system, mat)
            instance.setTickFn(() => {
              tickRefOpt.foreach { ref =>
                ref ! Tick(srcKey)
              }
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
            } catch {
              case _ =>
                parseError = true
            }

            exchanges.contains(srcKey) && !parseError
        }
        .groupBy(_.srcKey)
        .mapValues(_.map(_.topic).map(parseProductId).toSet)

      Right(SessionSetup(markets, dataSourceAddresses, dataSources,
        exchanges, strategyOpt.get, UUID.randomUUID.toString, Utils.currentTimeMicros))
    }

    def runSession(sessionSetup: SessionSetup): String = sessionSetup match {
      case SessionSetup(markets, dataSourceAddresses, dataSources, exchanges,
          strategy, sessionId, sessionMicros) =>
        var currentStrategySeqNr: Option[Long] = None

        println("running session")

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

                  case SessionReportEvent(event: TimeSeriesEvent) =>
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

        val iteratorExecutor: ExecutionContext =
          ExecutionContext.fromExecutor(
            Executors.newFixedThreadPool(dataSourceAddresses.size + 5))

        println("size:", dataSourceAddresses.size)

        var count = 0

        // Merge market data streams from the data sources we just loaded and stream the data into
        // the strategy instance. If this trading session is a backtest then we merge the data
        // streams by time. But if this is a live trading session then data is sent first come,
        // first serve to keep latencies low.
        val (tickRef, fut) = dataSourceAddresses
          .groupBy(_.srcKey)
          .flatMap { case (key, addresses) =>
            addresses.map {
              case a @ Address(_, topic, dataType) =>

                count += 1
                println("doing address", count, a)

                val it = dataSources(key).stream(dataDir, topic, dataType, mode match {
                  case Backtest(range) => range
                  case _ => TimeRange(sessionMicros)
                })

                Source.unfoldAsync[Iterator[MarketData], MarketData](it) { memo =>
                  Future {
                    if (memo.hasNext) {
                      Some(memo, memo.next)
                    } else None
                  } (iteratorExecutor)
                }
            }
          }
          .reduce[Source[MarketData, NotUsed]](mode match {
            case Backtest(range) => _.mergeSorted(_)
            case _ => _.merge(_)
          })
          .mergeMat(Source.actorRef[Tick](Int.MaxValue, OverflowStrategy.fail))(Keep.right)
            .map[Either[MarketData, Tick]] {
            case md: MarketData => Left(md)
            case tick: Tick => Right(tick)
          }
          .scan(Session()) { case (session@Session(seqNr, balances, prices, oms,
              actions, ids, _), dataOrTick) =>

            implicit val ctx: TradingSession = session

            // Split up `dataOrTick` into two Options
            val (tick: Option[Tick], data: Option[MarketData]) = dataOrTick match {
              case Right(t: Tick) => (Some(t), None)
              case Left(md: MarketData) => (None, Some(md))
            }

            val ex = data.map(_.source).getOrElse(tick.get.exchange)

            // Update the relevant exchange with the market data to collect fills and user data
            // TODO: This doesn't support non-exchange data sources yet
            val (fills, userData) = exchanges(ex).collect(session, data)

            // The user data is relayed to the strategy as StrategyEvents
            userData.foreach(strategy.handleEvent)

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
                try {
                  newPrices += (Market(pd.exchange, pd.product) -> pd.price)
                } catch {
                  case err: Throwable =>
//                    log.warning(s"Error retreiving price for ${pd.product}")
                }
                // TODO: Emit prices here
//                emitReportEvent(PriceEvent(pd.exchange, pd.product, pd.price, pd.micros))
              case _ =>
            }

            def acc(currency: String) = Account(ex, currency)

            userData.foldLeft((newIds, newActions)) {
              /**
                * Either market or limit order received by the exchange. Associate the client id
                * with the exchange id. Do not close any actions yet. Wait until the order is
                * open, in the case of limit order, or done if it's a market order.
                */
              case ((is, as), OrderReceived(id, _, clientId, _)) =>
                println("order received", id, clientId)
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
                println("order done", id)
                (is.updated(ex, is(ex).orderComplete(id)),
                  as.updated(ex, closeActionForOrderId(as(ex), is(ex), id)))

            } match {
              case (_newIds, _newActions) =>
                newIds = _newIds
                newActions = _newActions
            }

            newBalances = fills.foldLeft(newBalances) {
              case (bs, Fill(_, tradeId, fee, pair@Pair(base, quote), price, size,
                  micros, liquidity, side)) =>

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
                emitReportEvent(TradeEvent(tradeId, ex, pair.toString, micros, price, size))

                // Also balance info
                val rb = ret(acc(base))
                val rq = ret(acc(quote))
                val t = Utils.formatDate(new Date(micros / 1000))
//                println(s"emitting balance event for ($base, $quote) ($rb, $rq) at $t")
                emitReportEvent(BalanceEvent(acc(base), ret(acc(base)), micros))
                emitReportEvent(BalanceEvent(acc(quote), ret(acc(quote)), micros))

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
            log.info("session success")
            sessionEventsRef ! PoisonPill
          case Failure(err) =>
            log.error(err, "session failure")
            sessionEventsRef ! PoisonPill
        }

        // Return the session id
        sessionId
    }

    override def receive: Receive = {
      case "start" =>
        setup() match {
          case Left(err) =>
            sender ! err
          case Right(sessionSetup) =>
            sender ! (sessionSetup.sessionId, sessionSetup.sessionMicros)
            runSession(sessionSetup)
        }
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
}

trait TradingSession {
  import TradingSession._

  def id: String
  def balances: Map[Account, Double]
  def handleEvents(events: Event*): Unit
  def actionQueues: Map[String, ActionQueue]
}
