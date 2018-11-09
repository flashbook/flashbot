package io.flashbook.flashbot.controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import io.flashbook.flashbot.server.BotSocket
import javax.inject._
import io.flashbook.flashbot.shared.SharedMessages
import play.api.libs.streams.ActorFlow
import play.api.mvc._

@Singleton
class Application @Inject()(cc: ControllerComponents)
                           (implicit system: ActorSystem,
                            mat: Materializer) extends AbstractController(cc) {

  def index = Action {
    Ok(views.html.index(SharedMessages.itWorks))
  }

  def botSocket = WebSocket.accept[String, String] { request =>
    ActorFlow.actorRef { out => BotSocket.props(out) }
  }

}
