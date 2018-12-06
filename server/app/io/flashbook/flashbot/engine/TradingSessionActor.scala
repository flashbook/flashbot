package io.flashbook.flashbot.engine

import java.util.UUID

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.pattern.ask
import akka.util.Timeout
import io.circe._
import io.flashbook.flashbot.core.Action.{ActionQueue, CancelLimitOrder, PostLimitOrder, PostMarketOrder}
import io.flashbook.flashbot.core.DataSource.{DataSourceConfig, StreamSelection}
import io.flashbook.flashbot.core.Exchange.ExchangeConfig
import io.flashbook.flashbot.core.Instrument.CurrencyPair
import io.flashbook.flashbot.util.stream._
import io.flashbook.flashbot.util._
import io.flashbook.flashbot.util.time.currentTimeMicros
import io.flashbook.flashbot.core.{DataSource, _}
import io.flashbook.flashbot.engine.DataServer.{ClusterLocality, DataStreamReq}
import io.flashbook.flashbot.engine.TradingSession._
import io.flashbook.flashbot.exchanges.Simulator
import io.flashbook.flashbot.report.ReportEvent._
import io.flashbook.flashbot.report._
import io.flashbook.flashbot.service.Control

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, SyncVar}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class TradingSessionActor(strategyClassNames: Map[String, String],
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

    implicit val timeout: Timeout = Timeout(10 seconds)

    // Set the time. Using system time just this once.
    val sessionMicros = currentTimeMicros

    // Create the session loader
    val sessionLoader: SessionLoader = new SessionLoader()

    // Create default instruments for each currency pair configured for an exchange.
    def defaultInstruments(exchange: String): Set[Instrument] =
      exchangeConfigs(exchange).pairs.keySet.map(CurrencyPair(_))

    // Load a new instance of an exchange.
    def loadExchange(name: String): Try[Exchange] =
      sessionLoader.loadNewExchange(exchangeConfigs(name))
        .map(plainInstance => {
          // Wrap it in our Simulator if necessary.
          val instance = if (mode == Live) plainInstance else new Simulator(plainInstance)

          // Set the tick function. This is a hack that maybe we should remove later.
          instance.setTickFn(() => {
            emitTick(Tick(Seq.empty, Some(name)))
          })

          instance
        })

    for {
      // Check that we have a config for the requested strategy.
      strategyClassName <- strategyClassNames.get(strategyKey)
        .toTry(s"Unknown strategy: $strategyKey").toFut

      // Load the strategy
      strategy <- Future.fromTry[Strategy](sessionLoader.loadNewStrategy(strategyClassName))

      _ = {
        // Set the buffer
        strategy.buffer = new VarBuffer(initialReport.values.mapValues(_.value))

        // Load params
        strategy.loadParams(strategyParams)
      }

      // Initialize the strategy and collect data paths
      paths <- strategy.initialize(initialPortfolio, sessionLoader)

      // Load the exchanges
      exchangeNames: Set[String] = paths.toSet[DataPath].map(_.source).intersect(exchangeConfigs.keySet)
      exchanges: Map[String, Exchange] <- Future.sequence(exchangeNames.map(n =>
        loadExchange(n).map(n -> _).toFut)).map(_.toMap)

      // Load the instruments.
      instruments <- Future.sequence(exchanges.map { case (exName, ex) =>
        // Merge exchange provided instruments with default ones.
        ex.instruments.map((is: Set[Instrument]) => exName -> (defaultInstruments(exName) ++ is))})

        // Filter out any instruments that were not mentioned as a topic
        // in any of the subscribed data paths.
        .map(_.toMap.mapValues(_.filter((inst: Instrument) =>
            paths.map(_.topic).contains(inst.symbol))))

        // Remove empties.
        .map(_.filter(_._2.nonEmpty))

      // Ask the local data server to stream the data at the given paths.
      streams <- Future.sequence(paths.map((path: DataPath) => {
        val selection = mode match {
          case Backtest(range) => StreamSelection(path, range.from, range.to)
          case _ => StreamSelection(path, sessionMicros, polling = true)
        }
        (Control.dataServer.get ? DataStreamReq(selection, ClusterLocality))
          .map { case se: StreamResponse[MarketData[_]] => se.toSource }
      }))
    } yield
      SessionSetup(instruments, exchanges, strategy,
        UUID.randomUUID.toString, streams, sessionMicros)
  }

  def runSession(sessionSetup: SessionSetup): String = sessionSetup match {
    case SessionSetup(instruments, exchanges, strategy, sessionId, streams, sessionMicros) =>

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

      // Merge market data streams and send the the data into the strategy instance. If this
      // trading session is a backtest then we merge the data streams by time. But if this is a
      // live trading session then data is sent first come, first serve to keep latencies low.
      val (tickRef, fut) = streams.reduce[Source[MarketData[_], NotUsed]](mode match {
          case _:Backtest => _.mergeSorted(_)
          case _ => _.merge(_)
        })

        // Watch for termination of the merged data source stream and then manually close
        // the tick stream.
        .watchTermination()(Keep.right)
        .mapMaterializedValue(_.onComplete(res => {
          tickRefOpt.get ! PoisonPill
          res
        }))

        // Merge the tick stream into the main data stream.
        .mergeMat(Source.actorRef[Tick](Int.MaxValue, OverflowStrategy.fail))(Keep.right)

        // Just a little bit of type sanity. MarketData on the left. Ticks on the right.
        .map[Either[MarketData[_], Tick]] {
          case md: MarketData[_] => Left(md)
          case tick: Tick => Right(tick)
        }

        // Lift-off
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
          val (tick, data) = dataOrTick match {
            case Right(t: Tick) => (Some(t), None)
            case Left(md: MarketData[_]) => (None, Some(md))
          }

          // An optional string that represents the exchange tied to this scan iteration.
          val ex: Option[String] = data
            .map(_.source)
            .orElse(tick.flatMap(_.exchange))
            .filter(exchanges.isDefinedAt)
          val exchange: Option[Exchange] = ex.map(exchanges(_))

          // If this data has price info attached, emit that price info.
          data match {
            case Some(md: MarketData[Priced]) =>
              session.prices = session.prices.withPrice(Market(md.source, md.topic), md.data.price)
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
              val newPortfolio = instrument.settle(ex.get, fill, portfolio)

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
        .toMat(Sink.foreach { s: Session => })(Keep.both)
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

