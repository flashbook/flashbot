package core

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, Status}
import akka.persistence._
import akka.pattern.pipe
import akka.pattern.ask
import io.circe.{Encoder, Json}
import io.circe.parser.parse
import TradingSession._
import akka.Done
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.Timeout
import core.DataSource.DataSourceConfig
import core.Exchange.ExchangeConfig
import core.Report._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success}

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(dataDir: String,
                    strategyClassNames: Map[String, String],
                    dataSourceConfigs: Map[String, DataSourceConfig],
                    exchangeConfigs: Map[String, ExchangeConfig],
                    defaultBots: Map[String, BotConfig])
  extends PersistentActor with ActorLogging {

  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = Utils.buildMaterializer
  implicit val ec: ExecutionContext = system.dispatcher

  import TradingEngine._
  import scala.collection.immutable.Seq

  val snapshotInterval = 1000
  var state = EngineState(Map.empty)

  override def persistenceId: String = "trading-engine"

  private val engine = self

  /**
    * Turns an incoming command into a sequence of [[Event]] objects that affect the state in
    * some way and are then persisted, or into an [[EngineError]] to be returned to the sender.
    */
  def processCommand(command: Command): Either[EngineError, Seq[Event]] = command match {

    case StartEngine =>

      // Start the default bots
      defaultBots.foreach {
        case (name, BotConfig(strategy, mode, params, initial_balances)) =>

          // First of all, we look for any previous sessions for this bot. If one exists, then
          // take the balance from the last session as the initial balances for this session.
          // Otherwise, use the initial_balances from the bot config.
          val initialSessionBalances =
            state.bots.get(name).map(_.last.balances).getOrElse(initial_balances.map {
              case (key, v) =>
                val parts = key.split("/")
                (Account(parts(0), parts(1)), v)
            })

          // Create an actor that processes ReportEvents from this session.
          val (ref, fut) = Source
            .actorRef[ReportEvent](Int.MaxValue, OverflowStrategy.fail)
            .toMat(Sink.foreach { event =>
              println("Processing bot session event")
              self ! ProcessBotSessionEvent(name, event)
            })(Keep.both)
            .run

          fut onComplete {
            case Success(Done) =>
              log.info(s"Bot $name completed successfully")
            case Failure(err) =>
              log.error(err, s"Bot $name failed")
          }

          println("sending StartTradingSession")
          self ! StartTradingSession(
            Some(name),
            strategy,
            params,
            Mode(mode),
            ref,
            initialSessionBalances,
            Report.empty(strategy, params)
          )
      }
      Right(EngineStarted(Utils.currentTimeMicros) :: Nil)

    /**
      * A wrapper around a new SessionActor, which runs the actual strategy. This may be initiated
      * by either a backtest query or a running bot.
      */
    case StartTradingSession(
        botIdOpt,
        strategyKey,
        strategyParams,
        mode,
        sessionEventsRef,
        initialBalances,
        report
    ) =>

      val sessionActor = context.actorOf(Props(new SessionActor(
        dataDir,
        strategyClassNames,
        dataSourceConfigs,
        exchangeConfigs,
        strategyKey,
        strategyParams,
        mode,
        sessionEventsRef,
        initialBalances
      )))

      // Start the session. We are only waiting for an initialization error, or a confirmation
      // that the session was started, so we don't wait for too long. 1 second should do it.
      try {
        implicit val timeout: Timeout = Timeout(10 seconds)
        try {
          Await.result(sessionActor ? "start", timeout.duration) match {
            case (sessionId: String, micros: Long) =>
              Right(SessionStarted(sessionId, botIdOpt, strategyKey, strategyParams,
                mode, micros, initialBalances, report) :: Nil)
            case err: EngineError =>
              println("error on left", err)
              Left(err)
          }
        } catch {
          case err: Throwable =>
            println("ERRRRRRRRROR", err)
            throw err
        }
      } catch {
        case err: TimeoutException =>
          Left(EngineError("Trading session initialization timeout", err))
      }

    /**
      * A bot session emitted a ReportEvent. Here is where we decide what to do about it by
      * emitting the ReportDeltas that we'd like to persist in state. Specifically, if there
      * is a balance event, we want to save that to state. In addition to that, we always
      * generate report deltas and save those.
      */
    case ProcessBotSessionEvent(botId, event) =>
      if (!state.bots.isDefinedAt(botId)) {
        println("ignoring")
        return Right(Seq.empty)
      }

      val deltas = state.bots(botId).last.report.genDeltas(event)
        .map(ReportUpdated(botId, _))
        .toList

//      println("processing bot session event", event, deltas)

      Right(event match {
        case BalanceEvent(account, balance, micros) =>
          BalancesUpdated(botId, account, balance) :: deltas
        case _ => deltas
      })
  }

  /**
    * Persist every received command and occasionally save state snapshots so that the event log
    * doesn't grow out of bounds.
    */
  override def receiveCommand: Receive = {

    case err: EngineError =>
      log.error(err, "Uncaught EngineError")

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
      case Ping =>
        sender ! Pong

      case BotQuery(id) =>
        sender ! BotResponse(id, state.bots(id).map(_.report))

      case BotsQuery() =>
        sender ! BotsResponse(bots = state.bots.map { case (id, bot) =>
          BotResponse(id, bot.map(_.report))
        }.toSeq)

      case StrategiesQuery() =>
        sender ! StrategiesResponse(strategyClassNames.keys.map(StrategyResponse).toList)

      /**
        * To resolve a backtest query, we start a trading session in Backtest mode and collect
        * all session events into a stream that we fold over to create a report.
        */
      case BacktestQuery(strategyName, params, timeRange, balancesStr, barSize) =>

        try {
          // TODO: Handle parse errors
          val paramsJson = parse(params).right.get
          val report = Report.empty(strategyName, paramsJson, barSize)

          // Parse initial balances JSON into an Account -> Double map
          val balances: Map[Account, Double] = parse(balancesStr).right.get
            .as[Map[String, Double]].right.get
            .map { case (key, v) =>
              val parts = key.split("/")
              (Account(parts(0), parts(1)), v)
            }

          // Fold the empty report over the ReportEvents emitted from the session.
          val (ref: ActorRef, fut: Future[Report]) =
            Source.actorRef[ReportEvent](Int.MaxValue, OverflowStrategy.fail)
              .toMat(Sink.fold[Report, ReportEvent](report) {
                (report, event) => report.genDeltas(event).foldLeft(report)(_.update(_))
              })(Keep.both)
              .run

          // Start the trading session
          processCommand(StartTradingSession(None, strategyName, paramsJson,
            Backtest(timeRange), ref, balances, report)) match {
            case Left(err: EngineError) =>
              throw err
            case Right(events: Seq[Event]) =>
              events.foreach(println)
          }

          fut.map(ReportResponse) pipeTo sender
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
          println("SENDING BACK ERROR", cmd, err, sender)
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

  case class EngineState(bots: Map[String, Seq[TradingSessionState]],
                         startedAtMicros: Long = 0) {
    /**
      * A pure function that updates the state in response to an event that occurred in the
      * engine. No side effects or outside state please!
      */
    def update(event: Event): EngineState = event match {
      case EngineStarted(micros) =>
        copy(startedAtMicros = micros)

      case SessionStarted(id, Some(botId), strategyKey, strategyParams, mode,
          micros, balances, report) =>
        copy(bots = bots + (botId -> (
          bots.getOrElse[Seq[TradingSessionState]](botId, Seq.empty) :+
            TradingSessionState(id, strategyKey, strategyParams, mode, micros, balances, report))))

      case e: SessionUpdated => e match {
        case ReportUpdated(botId, delta) =>
          val bot = bots(botId)
          copy(bots = bots + (botId -> bot.updated(bot.length - 1,
            bot.last.updateReport(delta))))

        case BalancesUpdated(botId, account, balance) =>
          val bot = bots(botId)
          copy(bots = bots + (botId -> bot.updated(bot.length - 1,
            bot.last.copy(balances = bot.last.balances.updated(account, balance)))))
      }
    }
  }

  sealed trait Command
  case class StartTradingSession(botId: Option[String],
                                 strategyKey: String,
                                 strategyParams: Json,
                                 mode: Mode,
                                 sessionEvents: ActorRef,
                                 initialBalances: Map[Account, Double],
                                 report: Report) extends Command

  case object StartEngine extends Command
  case class ProcessBotSessionEvent(botId: String, event: ReportEvent) extends Command

  sealed trait Event
  case class SessionStarted(id: String,
                            botId: Option[String],
                            strategyKey: String,
                            strategyParams: Json,
                            mode: Mode,
                            micros: Long,
                            balances: Map[Account, Double],
                            report: Report) extends Event
  case class EngineStarted(micros: Long) extends Event

  sealed trait SessionUpdated extends Event {
    def botId: String
  }
  case class ReportUpdated(botId: String,
                           delta: ReportDelta) extends SessionUpdated
  case class BalancesUpdated(botId: String,
                             account: Account,
                             balance: Double) extends SessionUpdated



  sealed trait Query
  case object Ping extends Query
  case class BacktestQuery(strategyName: String,
                           params: String,
                           timeRange: TimeRange,
                           balances: String,
                           barSize: Option[Duration]) extends Query

  case class BotQuery(botId: String) extends Query
  case class BotsQuery() extends Query
  case class StrategiesQuery() extends Query

  sealed trait Response
  case object Pong extends Response {
    override def toString: String = "pong"
  }
  case class ReportResponse(report: Report) extends Response
  case class BotResponse(id: String, reports: Seq[Report]) extends Response
  case class BotsResponse(bots: Seq[BotResponse]) extends Response
  case class StrategyResponse(name: String) extends Response
  case class StrategiesResponse(strats: Seq[StrategyResponse]) extends Response

  final case class EngineError(message: String, cause: Throwable = None.orNull)
    extends Exception(message, cause) with Response

}
