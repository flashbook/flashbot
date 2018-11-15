package io.flashbook.flashbot

import java.net.URI

import io.circe.{Json, Printer}
import io.circe.generic.auto._
import io.circe.syntax._
import io.flashbook.flashbot.BacktestParams._
import io.flashbook.flashbot.api.BacktestSocketApi.BacktestSocketReq._
import io.flashbook.flashbot.util.json._
import org.scalajs.dom
import org.scalajs.dom.{Event, WebSocket}
import slinky.core._
import slinky.core.annotations.react
import slinky.web.html.{className, div}

@react class ReportView extends StatelessComponent {
  case class Props(name: String, params: Json)

  override def componentDidMount() = {

    val ws = new WebSocket(s"ws://${dom.window.location.host}/backtest/${BacktestConfig.strategy}/ws")

    ws.onopen = (e: Event) => {
      dom.window.location.search
      ws.send(printJson(RunBacktest(props.params)))
    }

    ws.onmessage = msg => {
      println(msg.data)
    }
  }

  def render() = div(className := "Report tile is-ancestor")(
    div(className := "tile is-vertical pane-parent left") (
      div(className := "tile pane"),
      div(className := "tile pane")
    ),
    div(className := "tile is-8") (
      div(className := "tile")
    ),
    div(className := "tile is-vertical pane-parent right") (
      div(className := "tile pane")
    )
  )
}

