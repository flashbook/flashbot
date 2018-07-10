package core

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, Status}
import akka.persistence._
import akka.pattern.pipe
import akka.pattern.ask
import io.circe.{Encoder, Json}
import io.circe.parser.parse
import io.circe.syntax._
import java.util.UUID

import TradingSession._
import akka.NotUsed
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import core.Action._
import core.DataSource.DataSourceConfig
import core.Exchange.ExchangeConfig
import core.Order._
import core.Report.ReportEvent
import core.Utils.parseProductId
import exchanges.Simulator

import scala.collection.immutable.Queue
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
  var state = EngineState()

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
        case (name, BotConfig(strategy, params, balances)) =>

          val (ref, fut) = Source
            .actorRef[ReportEvent](Int.MaxValue, OverflowStrategy.fail)
            .toMat(Sink.fold())(Keep.both)
            .run

          self ! StartTradingSession(
            strategy,
            params,
            Live,
            context.actorOf(Props[BotSessionReporter], "bot-reporter"),
            balances.map {
              case (key, v) =>
                val parts = key.split("/")
                (Account(parts(0), parts(1)), v)
            }
          )
      }
      Right(EngineStarted(Utils.currentTimeMicros) :: Nil)

    /**
      * A wrapper around a new SessionActor, which runs the actual strategy. This may be initiated
      * by either a backtest query or a running bot.
      */
    case StartTradingSession(
        strategyKey,
        strategyParams,
        mode,
        sessionEventsRef,
        initialBalances
    ) =>

      val sessionActor = context.actorOf(Props(new SessionActor(
        dataDir,
        strategyClassNames,
        dataSourceConfigs,
        exchangeConfigs,
        defaultBots,
        strategyKey,
        strategyParams,
        mode,
        sessionEventsRef,
        initialBalances
      )))

      // Start the session. We are only waiting for an initialization error, or a confirmation
      // that the session was started, so we don't wait for too long. 1 second should do it.
      try {
        Await.result(sessionActor ? "start", 1 second) match {
          case (sessionId: String, micros: Long) =>
            Right(SessionStarted(sessionId, strategyKey, strategyParams, mode, micros) :: Nil)
          case err: EngineError => Left(err)
        }
      } catch {
        case err: TimeoutException =>
          Left(EngineError("Trading session initialization timeout", err))
      }
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
      case BacktestQuery(strategyName, params, timeRange, balancesStr, barSize) =>

        try {
          // TODO: Handle parse errors
          val paramsJson = parse(params).right.get
          val emptyReport = Report(
            strategyName,
            params,
            barSize.getOrElse(1 minute),
            Vector(),
            Map.empty,
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
              .scan((emptyReport, Seq.empty)) {
                case ((report, deltas), event) => (report)
              }
              .drop(1)
              .toMat()
              .run

          processCommand(StartTradingSession(strategyName, paramsJson,
            Backtest(timeRange), ref, balances)) match {
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

  case class EngineState(bots: Map[String, Seq[TradingSessionRecord]],
                         startedAtMicros: Long = 0) {
    /**
      * A pure function that updates the state in response to an event that occurred in the
      * engine. No side effects or outside state please!
      */
    def update(event: Event): EngineState = event match {
      case EngineStarted(micros) =>
        copy(startedAtMicros = micros)

      case SessionStarted(id, strategyKey, strategyParams, mode, micros) =>
        copy(sessions = sessions + (id ->
          TradingSessionRecord(id, strategyKey, strategyParams, mode, micros)))
    }
  }

  sealed trait Command
  case class StartTradingSession(strategyKey: String,
                                 strategyParams: Json,
                                 mode: Mode,
                                 sessionEvents: ActorRef,
                                 initialBalances: Map[Account, Double]) extends Command

  case object StartEngine extends Command

  sealed trait Event
  sealed trait PersistedEvent extends Event
  case class SessionStarted(id: String,
                            strategyKey: String,
                            strategyParams: Json,
                            mode: Mode,
                            micros: Long) extends PersistedEvent
  case class EngineStarted(micros: Long) extends PersistedEvent





  sealed trait Query
  case object Ping extends Query
  case class BacktestQuery(strategyName: String,
                           params: String,
                           timeRange: TimeRange,
                           balances: String,
                           barSize: Option[Duration]) extends Query

  sealed trait Response
  case object Pong extends Response {
    override def toString: String = "pong"
  }
  case class ReportResponse(report: Report) extends Response

  final case class EngineError(private val message: String,
                               private val cause: Throwable = None.orNull)
    extends Exception(message, cause) with Response

}
