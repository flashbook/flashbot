package io.flashbook.flashbot.service

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.Cluster
import akka.stream.Materializer
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import javax.inject.{Inject, Singleton}
import play.api.Application
import play.api.inject.ApplicationLifecycle
import io.circe.Json
import io.circe.parser.parse
import io.circe.generic.auto._
import io.circe.literal._
import io.flashbook.flashbot.service._
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

  def start()(implicit config: Config, app: Application, system: ActorSystem): Unit = {

    val dataPath = config.getString("flashbot.dataPath")

    // Warn if the app is already started.
    if (appStarted.take()) {
      println("Warning: App already started")
    } else println("Starting App")

//    val systemConfig = config
//      .withValue("akka.persistence.journal.leveldb.dir",
//        ConfigValueFactory.fromAnyRef(s"$dataPath/journal"))
//      .withValue("akka.persistence.snapshot-store.local.dir",
//        ConfigValueFactory.fromAnyRef(s"$dataPath/snapshot-store"))

    val baseConfigJson: Json = parse(Source
      .fromInputStream(getClass.getResourceAsStream("/base_config.json"))
      .getLines.mkString).right.get

//    Source.fromFile("config.json", "utf-8").getLines.mkString
    val flashbotConfig = baseConfigJson
      .deepMerge(json"""{"exchanges": {}, "bots": {}}""")
      .as[ConfigFile].right.get

    def getStringListOpt(path: String): Option[Seq[String]] =
      if (config.getIsNull(path)) None else Some(config.getStringList(path).asScala)

    val activeBots = getStringListOpt("flashbot.activeBots")
    val activeDataSources = getStringListOpt("flashbot.activeDataSources")
    val activeTopics = getStringListOpt("flashbot.activeTopics")
    val activeDataTypes = getStringListOpt("flashbot.activeDataTypes")

    val finalBots = activeBots
      .map(bots => flashbotConfig.bots.filterKeys(bots.contains(_)))
      .getOrElse(flashbotConfig.bots)
    val finalDataSources = activeDataSources
      .map(dss => flashbotConfig.data_sources.filterKeys(dss.contains(_)))
      .getOrElse(flashbotConfig.data_sources)


    implicit val mat: Materializer = buildMaterializer
    implicit val ec: ExecutionContext = system.dispatcher

    // Start a TradingEngine
    val roles = getStringListOpt("akka.cluster.roles").get
    if (roles.contains("trading-engine")) {
      if (!engine.isSet) {
        engine.put(system.actorOf(Props(
          new TradingEngine(
            List(dataPath, "sources").mkString("/"),
            flashbotConfig.strategies,
            finalDataSources,
            flashbotConfig.exchanges,
            finalBots
          )
        ), "trading-engine"))
        engine.get ! StartEngine
      } else {
        println("Warning: Flashbot engine already started.")
      }
    }

    // Start a DataServer
    if (roles.contains("data-server")) {
      if (!dataServer.isSet) {
        dataServer.put(system.actorOf(Props(
          new DataServer()
        ), "data-server"))
      }

//      finalDataSources.keySet.foreach(srcName => {
//        val actor = system.actorOf(Props[IngestService], s"ingest:$srcName")
//        actor ! (
//          srcName,
//          List(dataPath, "sources").mkString("/"),
//          finalDataSources,
//          activeTopics.toSet,
//          activeDataTypes.toSet
//        )
//      })
    }

    appStarted.put(true)
  }

  def stop(): Unit = {

    println("ENDING")

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
      case rsp => Future.failed(new RuntimeException(s"Request type error for query $query"))
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
