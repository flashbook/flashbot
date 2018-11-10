package io.flashbook.flashbot.controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import io.flashbook.flashbot.server.BotSocket
import io.flashbook.flashbot.service.Control
import javax.inject._
import io.flashbook.flashbot.shared.SharedMessages
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import akka.pattern.pipe
import akka.pattern.ask
import akka.util.Timeout
import io.flashbook.flashbot.engine.TradingEngine.{BotSessionsResponse, EngineError}
import io.flashbook.flashbot.engine.TradingEngine

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

@Singleton
class Application @Inject()(cc: ControllerComponents)
                           (implicit system: ActorSystem,
                            mat: Materializer,
                            ec: ExecutionContext) extends AbstractController(cc) {

  implicit val timeout: Timeout = Timeout(5 seconds)

  def index = Action {
    Ok(views.html.index(SharedMessages.itWorks))
  }

  // Grab the latest session for the bot.
  def bot(bot: String) = Action.async {
    (Control.engine.get ? TradingEngine.BotSessionsQuery(bot)).map {
      case BotSessionsResponse(_, sessions) =>
        Ok(views.html.bot(bot, sessions.lastOption))
      case EngineError(message, cause) =>
        Ok(views.html.error(message, cause.map(_.getStackTrace)))
    }
  }

  def botSocket = WebSocket.accept[String, String] { request =>
    ActorFlow.actorRef { out => BotSocket.props(out) }
  }

}
