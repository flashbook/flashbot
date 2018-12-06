package io.flashbook.flashbot.engine

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.pattern.ask
import akka.util.Timeout
import io.circe.Json
import io.flashbook.flashbot.core.DataSource.{Bundle, DataClusterIndex, DataSourceIndex}
import io.flashbook.flashbot.core.Exchange
import io.flashbook.flashbot.core.Exchange.ExchangeConfig
import io.flashbook.flashbot.engine.DataServer.ClusterDataIndexReq
import io.flashbook.flashbot.engine.TradingEngine.EngineError
import io.flashbook.flashbot.service.Control

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class SessionLoader(implicit ec: ExecutionContext) {
  implicit val timeout = Timeout(10 seconds)

  def index: Future[DataSourceIndex] = {
    (Control.dataServer.get ? ClusterDataIndexReq).collect {
      case idx: DataClusterIndex => idx.slices
    }
  }

  protected[engine] def loadNewExchange(config: ExchangeConfig)
                                       (implicit system: ActorSystem,
                                        mat: ActorMaterializer): Try[Exchange] =
    try {
      Success(getClass.getClassLoader
        .loadClass(config.`class`)
        .asSubclass(classOf[Exchange])
        .getConstructor(classOf[Json], classOf[ActorSystem], classOf[ActorMaterializer])
        .newInstance(config.params, system, mat)
      )
    } catch {
      case err: ClassNotFoundException =>
        Failure(EngineError("Exchange class not found: " + config.`class`, Some(err)))
      case err: ClassCastException =>
        Failure(EngineError(s"Class ${config.`class`} must be a " +
          s"subclass of io.flashbook.core.Exchange", Some(err)))
    }

  protected[engine] def loadNewStrategy(className: String): Try[Strategy] =
    try {
      Success(getClass.getClassLoader
        .loadClass(className)
        .asSubclass(classOf[Strategy])
        .newInstance())
    } catch {
      case err: ClassNotFoundException =>
        Failure(EngineError(s"Strategy class not found: $className", Some(err)))

      case err: ClassCastException =>
        Failure(EngineError(s"Class $className must be a " +
          s"subclass of io.flashbook.core.Strategy", Some(err)))
      case err => Failure(err)
    }
}
