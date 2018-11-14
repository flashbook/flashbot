package io.flashbook.flashbot

import io.circe.Printer
import io.flashbook.flashbot.BacktestParams.StratParams
import io.flashbook.flashbot.BacktestParams._
import org.scalajs.dom
import org.scalajs.dom.{Event, WebSocket}
import slinky.core._
import slinky.core.annotations.react
import slinky.web.html.{className, div}


@react class ReportView extends StatelessComponent {
  case class Props(name: String)

  override def componentDidMount() = {

    val ws = new WebSocket(s"ws://${dom.window.location.host}/backtest/${BacktestConfig.strategy}/ws")
    ws.onopen = (e: Event) => {
      val msg: String = paramsEn(???).pretty(Printer.noSpaces)
      ws.send(msg)
    }
    ws.onmessage = msg => {
      println(msg)
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

