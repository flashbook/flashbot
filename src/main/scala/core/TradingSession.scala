package core

import java.util.{Date, UUID}
import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill}
import akka.stream.{ActorAttributes, ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source}
import core.Action.{ActionQueue, CancelLimitOrder, PostLimitOrder, PostMarketOrder}
import core.AggBook.{AggBook, AggBookMD}
import core.DataSource.{Address, DataSourceConfig}
import core.Exchange.ExchangeConfig
import core.Order.{Buy, Fill, Sell}
import core.Report._
import core.TradingEngine._
import core.Utils.parseProductId
import exchanges.Simulator
import io.circe.Json

import scala.collection.immutable.Queue
import scala.concurrent.forkjoin.ForkJoinPool
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object TradingSession {

  trait Event
  case class LogMessage(message: String) extends Event
  case class OrderTarget(exchangeName: String,
                         targetId: TargetId,
                         size: Size,
                         price: Option[Double],
                         postOnly: Boolean) extends Event
  case class SetHedge(coin: String, position: Long) extends Event
  case class SessionReportEvent(event: ReportEvent) extends Event

//  def exchangeNameForTargetId(id: String): String = id.split(":").head

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
    def updateReport(delta: ReportDelta): TradingSessionState =
      copy(report = report.update(delta))
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

//    implicit val ec: ExecutionContext =
//      ExecutionContext.fromExecutor(Executors.newFixedThreadPool(100))
    implicit val ec: ExecutionContext = ExecutionContext.global
    implicit val system: ActorSystem = context.system
    implicit val mat: ActorMaterializer = Utils.buildMaterializer

    val dataSourceClassNames: Map[String, String] = dataSourceConfigs.mapValues(_.`class`)
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
        println("** 1")
        // TODO: Should we hide data source config expansion behind a flag?
        // Or be smart about it some other way?

//        val expandedDataSourceConfigs = dataSourceConfigs.map {
//          case (k, c) => (k, c.copy(topics = c.topics.flatMap {
//            case ("*", value: Json) =>
//              val foo  = loadDataSource(k)
//              println("** 2", k, foo)
//              val res = foo.index.toSeq.map((_, value))
//              println("** 2 res", res)
//              res
//            case kv: (String, Json) =>
//              println("** 3")
//              Seq(kv)
//          }))
//        }
        println("** 4")
        dataSourceAddresses = strategyOpt.get
          .initialize(strategyParams, dataSourceConfigs, initialBalances)
          .map(DataSource.parseAddress)
      } catch {
        case err: EngineError =>
          return Left(err)
        case err: Throwable =>
          println("CAUSE", err.getMessage)
//          err.getCause.printStackTrace()
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
              else new Simulator(instance)))
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

                           emittedReportEvents: Seq[ReportEvent] = Seq.empty)

          extends TradingSession {

          override val id: String = sessionId

          override def handleEvents(events: TradingSession.Event*): Unit =
            events.foreach(handleEvent)

          private var targets = Queue.empty[OrderTarget]
          private var reportEvents = Queue.empty[ReportEvent]

          var hedges = Map.empty[String, Double]

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

                  case SessionReportEvent(event: TimeSeriesCandle) =>
                    reportEvents = reportEvents.enqueue(event.copy(key = "local." + event.key))

                  case msg: LogMessage =>
                    // TODO: This should save to a time log, not log to stdout...
                    log.info(msg.message)

                  case h: SetHedge =>
                    hedges += (h.coin -> h.position)
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

        // Merge market data streams from the data sources we just loaded and stream the data into
        // the strategy instance. If this trading session is a backtest then we merge the data
        // streams by time. But if this is a live trading session then data is sent first come,
        // first serve to keep latencies low.
        val (tickRef, fut) = dataSourceAddresses
          .groupBy(_.srcKey)
          .flatMap { case (key, addresses) =>
            addresses.map { addr =>
              val resolved = strategy.resolveAddress(addr)
              val it =
                if (resolved.isDefined) resolved.get
                else dataSources(key).stream(dataDir, addr.topic, addr.dataType, mode match {
                  case Backtest(range) => range
                  case _ => TimeRange(sessionMicros)
                })

              Source.unfoldAsync[Iterator[MarketData], MarketData](it) { memo =>
                Future {
                  if (memo.hasNext) {
                    Some(memo, memo.next)
                  } else {
                    None
                  }
                } (iteratorExecutor)
              }
            }
          }
          .reduce[Source[MarketData, NotUsed]](mode match {
            case Backtest(range) => _.mergeSorted(_)
            case _ => _.merge(_)
          })

          // Watch for termination of the merged data source stream and then manually close
          // the tick stream.
          .watchTermination()(Keep.right)
          .mapMaterializedValue(_.onComplete(res => {
            tickRefOpt.get ! PoisonPill
            res
          }))

          .mergeMat(Source.actorRef[Tick](Int.MaxValue, OverflowStrategy.fail))(Keep.right)
            .map[Either[MarketData, Tick]] {
            case md: MarketData => Left(md)
            case tick: Tick => Right(tick)
          }
          .scan(Session()) { case (session@Session(
              seqNr, balances, prices, oms, actions, _), dataOrTick) =>

            implicit val ctx: TradingSession = session

            // Split up `dataOrTick` into two Options
            val (tick: Option[Tick], data: Option[MarketData]) = dataOrTick match {
              case Right(t: Tick) => (Some(t), None)
              case Left(md: MarketData) => (None, Some(md))
            }

            val ex = data.map(_.source).getOrElse(tick.get.exchange)

            var newPrices = prices
            var newOMs = oms
            var newActions = actions

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

            // Update the relevant exchange with the market data to collect fills and user data
            // TODO: This doesn't support non-exchange data sources yet
            val (fills, userData) = exchanges(ex).collect(session, data)
            userData.foldLeft((newOMs, newActions)) {
              /**
                * Either market or limit order received by the exchange. Associate the client id
                * with the exchange id. Do not close any actions yet. Wait until the order is
                * open, in the case of limit order, or done if it's a market order.
                */
              case ((os, as), o @ OrderReceived(id, _, clientId, _)) =>
                strategy.handleEvent(StrategyOrderEvent(os(ex).ids.clientToTarget(clientId.get), o))
                (os.updated(ex, os(ex).receivedOrder(clientId.get, id)), as)

              /**
                * Limit order is opened on the exchange. Close the action that submitted it.
                */
              case ((os, as), o @ OrderOpen(id, _, _, _, _)) =>
                strategy.handleEvent(StrategyOrderEvent(os(ex).ids.actualToTarget(id), o))
                (os.updated(ex, os(ex).openOrder(o)),
                  as.updated(ex, closeActionForOrderId(as(ex), os(ex).ids, id)))

              /**
                * Either market or limit order is done. Could be due to a fill, or a cancel.
                * Disassociate the ids to keep memory bounded in the ids manager. Also close
                * the action for the order id.
                */
              case ((os, as), o @ OrderDone(id, _, _, _, _, _)) =>
                strategy.handleEvent(StrategyOrderEvent(os(ex).ids.actualToTarget(id), o))
                (os.updated(ex, os(ex).orderComplete(id)),
                  as.updated(ex, closeActionForOrderId(as(ex), os(ex).ids, id)))

            } match {
              case (_newOMs, _newActions) =>
                newOMs = _newOMs
                newActions = _newActions
            }

            var newReportEvents = Queue.empty[ReportEvent]
            def emitReportEvent(e: ReportEvent): Unit = {
              newReportEvents = newReportEvents.enqueue(e)
            }

            def acc(currency: String) = Account(ex, currency)
            val newBalances = fills.foldLeft(balances) {
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

            // Use a sequence number to enforce the rule that Session.handleEvents is only
            // allowed to be called in the current call stack. Send market data to strategy,
            // then lock the handleEvent method again.
            val sessionInstance = session.copy(
              prices = newPrices,
              balances = newBalances
            )

            data match {
              case Some(md) =>
                currentStrategySeqNr = Some(session.seqNr)
                try {
                  strategy.handleData(md)(sessionInstance)
                } catch {
                  case e: Throwable =>
                    println(e.getMessage)
                }
                currentStrategySeqNr = None
              case None =>
            }

            // Collected report events and targets should be empty if `handleData` wasn't called.
            newReportEvents ++= sessionInstance.collectReportEvents

            // Send the order targets to the corresponding order manager.
            sessionInstance.collectTargets.foreach { target =>
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
                    .map { case (market, value) => (market.product, value) },
                  sessionInstance.hedges
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
                    case action @ PostMarketOrder(clientId, targetId, side, size, funds) =>
                      newOMs += (exName -> newOMs(exName).initCreateOrder(targetId, clientId, action))
                      exchanges(exName).order(
                        MarketOrderRequest(clientId, side, targetId.pair, size, funds))

                    case action @ PostLimitOrder(clientId, targetId, side, size, price, postOnly) =>
                      newOMs = newOMs.updated(exName, newOMs(exName).initCreateOrder(targetId, clientId, action))
                      exchanges(exName)
                        .order(LimitOrderRequest(clientId, side, targetId.pair, size, price, postOnly))

                    case CancelLimitOrder(targetId) =>
                      newOMs = newOMs.updated(exName, newOMs(exName).initCancelOrder(targetId))
                      exchanges(exName)
                        .cancel(newOMs(exName).ids.actualIdForTargetId(targetId), targetId.pair)
                  }
                case _ =>
              }
            }

            sessionInstance.copy(
              seqNr = seqNr + 1,
              orderManagers = newOMs,
              actionQueues = newActions,
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
            println("YOOOO")
            log.info("session success")
            sessionEventsRef ! PoisonPill
          case Failure(err) =>
            println("YOOOO HELLLOOOO")
            log.error(err, "session failed")
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
