package io.flashbook.flashbot.engine

import java.util.concurrent.Executors
import java.util.UUID

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import io.circe.Json
import io.flashbook.flashbot.core.Action.{ActionQueue, CancelLimitOrder, PostLimitOrder, PostMarketOrder}
import io.flashbook.flashbot.core.DataSource.DataSourceConfig
import io.flashbook.flashbot.core.Exchange.ExchangeConfig
import io.flashbook.flashbot.core.Instrument.CurrencyPair
import io.flashbook.flashbot.util.stream._
import io.flashbook.flashbot.util.time.currentTimeMicros
import io.flashbook.flashbot.core._
import io.flashbook.flashbot.engine.TradingEngine.EngineError
import io.flashbook.flashbot.engine.TradingSession._
import io.flashbook.flashbot.exchanges.Simulator
import io.flashbook.flashbot.report.ReportEvent._
import io.flashbook.flashbot.report._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, SyncVar}
import scala.util.{Failure, Success}

class TradingSessionActor(dataDir: String,
                          strategyClassNames: Map[String, String],
                          dataSourceConfigs: Map[String, DataSourceConfig],
                          exchangeConfigs: Map[String, ExchangeConfig],
                          strategyKey: String,
                          strategyParams: Json,
                          mode: Mode,
                          sessionEventsRef: ActorRef,
                          initialPortfolio: Portfolio,
                          initialReport: Report) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = buildMaterializer

  // Setup a thread safe reference to an event buffer which allows the session to process
  // events synchronously when possible.
  val eventBuffer: SyncVar[mutable.Buffer[Any]] = new SyncVar[mutable.Buffer[Any]]

  val dataSourceClassNames: Map[String, String] = dataSourceConfigs.mapValues(_.`class`)

  // Allows the session to respond to events other than incoming market data. Exchange instances
  // can send ticks to let the session know that they have events ready to be collected. Strategies
  // can also send events to the session. These are stored in tick.event.
  var tickRefOpt: Option[ActorRef] = None
  def emitTick(tick: Tick) = {
    tickRefOpt match {
      case Some(ref) => ref ! tick
      case None => log.warning(s"Ignoring tick $tick because the tick ref actor is not loaded.")
    }
  }

  def setup(): Future[SessionSetup] = {

    // Check that we have a config for the requested strategy.
    if (!strategyClassNames.isDefinedAt(strategyKey)) {
      return Future.failed(EngineError(s"Unknown strategy: $strategyKey"))
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
        return Future.failed(
          EngineError(s"Strategy class not found: ${strategyClassNames(strategyKey)}", Some(err)))

      case err: ClassCastException =>
        return Future.failed(EngineError(s"Class ${strategyClassNames(strategyKey)} must be a " +
          s"subclass of io.flashbook.core.Strategy", Some(err)))
    }

    // Instantiate the strategy
    try {
      strategyOpt = Some(strategyClassOpt.get.newInstance)
      strategyOpt.get.buffer = Some(new VarBuffer(initialReport.values.mapValues(_.value)))
    } catch {
      case err: Throwable =>
        return Future.failed(EngineError(s"Strategy instantiation error: $strategyKey", Some(err)))
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
            s"${dataSourceClassNames(srcKey)}", Some(err))
        case err: ClassCastException =>
          throw EngineError(s"Class ${dataSourceClassNames(srcKey)} must be a " +
            s"subclass of io.flashbook.core.DataSource", Some(err))
      }

      try {
        val dataSource = dataSourceClassOpt.get.newInstance
        dataSources = dataSources + (srcKey -> dataSource)
        dataSource
      } catch {
        case err: Throwable =>
          throw EngineError(s"Data source instantiation error: $srcKey", Some(err))
      }
    }

    // Create the session loader
    val sessionLoader = new SessionLoader()

    // Initialize the strategy and collect the data source addresses it returns
    strategyOpt.get
      .initialize(strategyParams, initialPortfolio, sessionLoader)
      .map(_.map(DataSource.parseAddress))
      .flatMap { dataSourceAddresses =>
        // Initialization validation
        if (dataSourceAddresses.isEmpty) {
          return Future.failed(EngineError(s"Strategy must declare at least one data source " +
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
              return Future.failed(err)
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
                return Future.failed(EngineError("Exchange class not found: " + exClass, Some(err)))
              case err: ClassCastException =>
                return Future.failed(EngineError(s"Class $exClass must be a " +
                  s"subclass of io.flashbook.core.Exchange", Some(err)))
            }

            try {
              val instance: Exchange = classOpt.get.getConstructor(
                classOf[Json], classOf[ActorSystem], classOf[ActorMaterializer])
                .newInstance(exchangeConfigs(srcKey).params, system, mat)
              val finalExchangeInstance =
                if (mode == Live) instance
                else new Simulator(instance)
              finalExchangeInstance.setTickFn(() => {
                emitTick(Tick(Seq.empty, Some(srcKey)))
              })
              exchanges = exchanges + (srcKey -> finalExchangeInstance)
            } catch {
              case err: Throwable =>
                return Future.failed(
                  EngineError(s"Exchange instantiation error: $srcKey", Some(err)))
            }
          }
        }

        def defaultInstruments(exchange: String): Set[Instrument] =
          exchangeConfigs(exchange).pairs.keySet.map(CurrencyPair(_))

        Future.sequence(exchanges
          .map { case (k, v) =>
            v.instruments.map((is: Set[Instrument]) => k -> (defaultInstruments(k) ++ is))
          })
          .map(_.toMap.mapValues(_.filter(instrument =>
            dataSourceAddresses.map(_.topic).contains(instrument.symbol))))
          .map(_.filterNot(_._2.isEmpty))
          .map(SessionSetup(
            _, dataSourceAddresses, dataSources,
            exchanges, strategyOpt.get, UUID.randomUUID.toString, currentTimeMicros
          ))
      }

  }

  def runSession(sessionSetup: SessionSetup): String = sessionSetup match {
    case SessionSetup(instruments, dataSourceAddresses, dataSources, exchanges,
        strategy, sessionId, sessionMicros) =>

      /**
        * The trading session that we fold market data over.
        */
      class Session(val instruments: InstrumentIndex = instruments,
                    protected[engine] var portfolio: Portfolio = initialPortfolio,
                    protected[engine] var prices: PriceIndex = PriceIndex.empty,
                    protected[engine] var orderManagers: Map[String, TargetManager] =
                        instruments.instruments.mapValues(_ => TargetManager(instruments)),
                    // Create action queue for every exchange
                    protected[engine] var actionQueues: Map[String, ActionQueue] =
                        instruments.instruments.mapValues(_ => ActionQueue()),
                    protected[engine] var emittedReportEvents: Seq[ReportEvent] = Seq.empty)
        extends TradingSession {

        override val id: String = sessionId

        /**
          * Events sent to the session are either emitted as a Tick, or added to the event buffer
          * if we can, so that the strategy can process events synchronously when possible.
          */
        protected[engine] var sendFn: Seq[Any] => Unit = { _ =>
          throw new RuntimeException("sendFn not defined yet.")
        }

        def send(events: Any*): Unit = {
          sendFn(events)
        }

        override def getPortfolio = portfolio

        override def getActionQueues = actionQueues

        override def getPrices = prices
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
        .scan(new Session()) { case (session, dataOrTick) =>

          // First, setup the event buffer so that we can handle synchronous events.
          val thisEventBuffer: mutable.Buffer[Any] = new ArrayBuffer[Any]()
          eventBuffer.put(thisEventBuffer)

          // Set the session `sendFn` function. Close over `thisEventBuffer` and check that it
          // matches the one in the syncvar. Only then can we append to it, otherwise, emit it
          // as an async tick.
          session.sendFn = (events: Seq[Any]) => {
            if (eventBuffer.isSet) {
              val buf = eventBuffer.get(5L)
              // Check the syncvar for reference equality with our buffer.
              if (buf.isDefined && (buf.get eq thisEventBuffer)) {
                thisEventBuffer ++= events
              } else {
                emitTick(Tick(events))
              }
            } else {
              emitTick(Tick(events))
            }
          }

          implicit val ctx: TradingSession = session

          // Split up `dataOrTick` into two Options
          val (tick: Option[Tick], data: Option[MarketData]) = dataOrTick match {
            case Right(t: Tick) => (Some(t), None)
            case Left(md: MarketData) => (None, Some(md))
          }

          // An optional string that represents the exchange tied to this scan iteration.
          val ex: Option[String] = data
            .map(_.source)
            .orElse(tick.flatMap(_.exchange))
            .filter(exchanges.isDefinedAt)
          val exchange: Option[Exchange] = ex.map(exchanges(_))

          // If this data has price info attached, emit that price info.
          data match {
            case Some(pd: Priced) => // I love pattern matching on types.
              session.prices = session.prices.withPrice(Market(pd.exchange, pd.product), pd.price)
              // TODO: Emit prices here
//                emitReportEvent(PriceEvent(pd.exchange, pd.product, pd.price, pd.micros))
            case _ =>
          }

          // Update the relevant exchange with the market data to collect fills and user data
          val (fills, userData) = exchange
            .map(_.collect(session, data))
            .getOrElse((Seq.empty, Seq.empty))

          userData.foldLeft((session.orderManagers, session.actionQueues)) {
            /**
              * Either market or limit order received by the exchange. Associate the client id
              * with the exchange id. Do not close any actions yet. Wait until the order is
              * open, in the case of limit order, or done if it's a market order.
              */
            case ((os, as), o @ OrderReceived(id, _, clientId, _)) =>
              strategy.handleEvent(StrategyOrderEvent(os(ex.get).ids.clientToTarget(clientId.get), o))
              (os.updated(ex.get, os(ex.get).receivedOrder(clientId.get, id)), as)

            /**
              * Limit order is opened on the exchange. Close the action that submitted it.
              */
            case ((os, as), o @ OrderOpen(id, _, _, _, _)) =>
              strategy.handleEvent(StrategyOrderEvent(os(ex.get).ids.actualToTarget(id), o))
              (os.updated(ex.get, os(ex.get).openOrder(o)),
                as.updated(ex.get, closeActionForOrderId(as(ex.get), os(ex.get).ids, id)))

            /**
              * Either market or limit order is done. Could be due to a fill, or a cancel.
              * Disassociate the ids to keep memory bounded in the ids manager. Also close
              * the action for the order id.
              */
            case ((os, as), o @ OrderDone(id, _, _, _, _, _)) =>
              strategy.handleEvent(StrategyOrderEvent(os(ex.get).ids.actualToTarget(id), o))
              (os.updated(ex.get, os(ex.get).orderComplete(id)),
                as.updated(ex.get, closeActionForOrderId(as(ex.get), os(ex.get).ids, id)))

          } match {
            case (newOMs, newActions) =>
              session.orderManagers = newOMs
              session.actionQueues = newActions
          }

          def acc(currency: String) = Account(ex.get, currency)
          session.portfolio = fills.foldLeft(session.portfolio) {
            case (portfolio, fill) =>
              // Execute the fill on the portfolio
              val instrument = instruments(ex.get, fill.instrument)
              val newPortfolio = instrument.execute(ex.get, fill, portfolio)

              // Emit a trade event when we see a fill
              session.send(TradeEvent(
                fill.tradeId, ex.get, fill.instrument.toString,
                fill.micros, fill.price, fill.size))

              // Emit portfolio info:
              // The settlement account must be an asset
              val settlementAccount = acc(instrument.settledIn)
              session.send(BalanceEvent(settlementAccount,
                newPortfolio.balance(settlementAccount), fill.micros))

              // The security account may be a position or an asset
              val market = Market(ex.get, instrument.symbol)
              val position = portfolio.positions.get(market)
              if (position.isDefined) {
                session.send(PositionEvent(market, position.get, fill.micros))
              } else {
                val assetAccount = acc(instrument.security.get)
                session.send(BalanceEvent(assetAccount,
                  portfolio.balance(assetAccount), fill.micros))
              }

              // And calculate our equity.
              session.send(TimeSeriesEvent("equity_usd",
                newPortfolio.equity()(session.prices), fill.micros))

              // Return updated portfolio
              newPortfolio
          }

          // Call handleData and catch user errors.
          data match {
            case Some(md) =>
              try {
                strategy.handleData(md)(session)
              } catch {
                case e: Throwable =>
                  e.printStackTrace()
              }
            case None =>
          }

          // Take our event buffer from the syncvar. This allows the next scan iteration to `put`
          // theirs in. Otherwise, `put` will block. Append to the events we received from a tick.
          val events = tick.map(_.events).getOrElse(Seq.empty) ++ eventBuffer.take()

          // Process the events.
          events foreach {
            // Send report events to the events actor ref.
            case SessionReportEvent(reportEvent) =>
              val re = reportEvent match {
                case tsEvent: TimeSeriesEvent =>
                  tsEvent.copy(key = "local." + tsEvent.key)
                case tsCandle: TimeSeriesCandle =>
                  tsCandle.copy(key = "local." + tsCandle.key)
                case otherReportEvent => otherReportEvent
              }
              sessionEventsRef ! re

            // Send order targets to their corresponding order manager.
            case target: OrderTarget =>
              session.orderManagers += (target.market.exchange ->
                session.orderManagers(target.market.exchange).submitTarget(target))

            case LogMessage(msg) =>
              log.info(msg)
          }

          // If necessary, expand the next target into actions and enqueue them for each
          // order manager.
          session.orderManagers.foreach {
            case (exName, om) =>
              om.enqueueActions(exchanges(exName), session.actionQueues(exName))(
                session.prices, session.instruments) match {
                  case (newOm, newActions) =>
                    session.orderManagers += (exName -> newOm)
                    session.actionQueues += (exName -> newActions)
                }
          }

          // Here is where we tell the exchanges to do stuff, like place or cancel orders.
          session.actionQueues.foreach { case (exName, acs) =>
            acs match {
              case ActionQueue(None, next +: rest) =>
                session.actionQueues += (exName -> ActionQueue(Some(next), rest))
                next match {
                  case action @ PostMarketOrder(clientId, targetId, side, size, funds) =>
                    session.orderManagers += (exName ->
                      session.orderManagers(exName).initCreateOrder(targetId, clientId, action))
                    exchanges(exName).order(
                      MarketOrderRequest(clientId, side, targetId.instrument, size, funds))

                  case action @ PostLimitOrder(clientId, targetId, side, size, price, postOnly) =>
                    session.orderManagers += (exName ->
                      session.orderManagers(exName).initCreateOrder(targetId, clientId, action))
                    exchanges(exName)
                      .order(LimitOrderRequest(clientId, side, targetId.instrument, size, price, postOnly))

                  case CancelLimitOrder(targetId) =>
                    session.orderManagers += (exName ->
                      session.orderManagers(exName).initCancelOrder(targetId))
                    exchanges(exName).cancel(
                      session.orderManagers(exName).ids.actualIdForTargetId(targetId), targetId.instrument)
                }
              case _ =>
            }
          }

          session
        }
        .drop(1)
        .toMat(Sink.foreach { s => })(Keep.both)
        .run

      tickRefOpt = Some(tickRef)

      fut.onComplete {
        case Success(_) =>
          log.info("session success")
          sessionEventsRef ! PoisonPill
        case Failure(err) =>
          log.error(err, "session failed")
          sessionEventsRef ! PoisonPill
      }

      // Return the session id
      sessionId
  }

  override def receive: Receive = {
    case "start" =>
      setup().onComplete {
        case Success(sessionSetup) =>
          sender ! (sessionSetup.sessionId, sessionSetup.sessionMicros)
          runSession(sessionSetup)
        case Failure(err) =>
          sender ! err
      }
  }
}

