package io.flashbook.flashbot.service

import java.io.File

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.Cluster
import akka.stream.Materializer
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory, ConfigValueType}
import com.typesafe.config.ConfigValueType._
import javax.inject.{Inject, Singleton}
import play.api.Application
import play.api.inject.ApplicationLifecycle
import io.circe.Json
import io.circe.parser.parse
import io.circe.generic.auto._
import io.circe.literal._
import io.flashbook.flashbot.core.DataPath
import io.flashbook.flashbot.service._
import io.flashbook.flashbot.util.files._
import io.flashbook.flashbot.engine.TradingEngine.StartEngine
import io.flashbook.flashbot.engine.{DataServer, IngestService, TradingEngine, TradingSession}
import io.flashbook.flashbot.util.stream.buildMaterializer

import scala.concurrent.{ExecutionContext, Future, SyncVar}
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.io.Source

/**
  * We can't trust Guice dependency injection of Singletons happening only once.
  * Going the paranoid option.
  */
object Control {
  val appStarted = new SyncVar[Boolean]
  appStarted.put(false)

  val engine = new SyncVar[ActorRef]
  val dataServer = new SyncVar[ActorRef]

  val tmpDataDir = new SyncVar[File]

  def start()(implicit config: Config, app: Application, system: ActorSystem): Unit = {

    // Warn if the app is already started.
    if (appStarted.take()) {
      println("Warning: App already started")
    } else println("Starting App")

    val baseConfigJson: Json = parse(
      Source.fromInputStream(getClass.getResourceAsStream("/base_config.json"))
        .getLines.mkString
    ).right.get

    val flashbotConfig: ConfigFile = baseConfigJson
      .deepMerge(json"""{"exchanges": {}, "bots": {}}""")
      .as[ConfigFile].right.get

    def ingestMatchers: Set[DataPath] =
      config.getStringList("flashbot.ingest").asScala.map(DataPath.parse).toSet

    val defaultBotMatchers = config.getStringList("flashbot.defaultBots").asScala.toSet
      .map((re: String) => ("^" + re + "$").r)
    val defaultBots = flashbotConfig.bots.filterKeys(key =>
      defaultBotMatchers.exists(_.findFirstIn(key).isDefined))

    implicit val mat: Materializer = buildMaterializer
    implicit val ec: ExecutionContext = system.dispatcher

    val roles = config.getStringList("akka.cluster.roles")

    // Start a DataServer
    if (!dataServer.isSet) {
      dataServer.put(system.actorOf(Props(new DataServer(
        new File(config.getString("flashbot.marketDataPath")),
        flashbotConfig.data_sources,
        if (roles.contains("data-ingest")) ingestMatchers else Set.empty
      )), "data-server"))
    }


    // Start a TradingEngine
    if (roles.contains("trading-engine")) {
      if (!engine.isSet) {
        engine.put(system.actorOf(Props(
          new TradingEngine(
            flashbotConfig.strategies,
            flashbotConfig.exchanges,
            defaultBots
          )
        ), "trading-engine"))
        engine.get ! StartEngine
      } else {
        println("Warning: Flashbot engine already started.")
      }
    }

    appStarted.put(true)
  }

  def stop(): Unit = {
    if (!appStarted.take()) {
      println("Warning: App already stopped")
    }

    if (engine.isSet) {
      engine.take() ! PoisonPill
    }

    if (dataServer.isSet) {
      dataServer.take() ! PoisonPill
    }

    appStarted.put(false)
  }

  implicit val timeout: Timeout = Timeout(5 seconds)
  def request[T <: TradingEngine.Response](query: TradingEngine.Query)
             (implicit ec: ExecutionContext): Future[T] =
    (engine.get ? query).flatMap {
      case err: TradingEngine.EngineError => Future.failed(err)
      case err: Throwable => Future.failed(err)
      case rsp: T => Future.successful(rsp)
      case rsp => Future.failed(
        new RuntimeException(s"Request type error for query $query. Unexpected response $rsp."))
    }
}

/**
  * Idempotent collector of of DI objects. This just feeds data to our thread safe Control object.
  * Must be idempotent to handle the infuriating case where "Singletons" are instantiated twice.
  */
@Singleton
class Control @Inject()() (implicit config: Config,
                           lifecycleState: ApplicationLifecycle,
                           system: ActorSystem,
                           ec: ExecutionContext,
                           app: Application) {
  Control.start()
  lifecycleState.addStopHook(() => {
    Control.stop()
    Future.successful(true)
  })
}
