package io.flashbook.flashbot

import org.scalajs.dom
import slinky.core._
import slinky.core.annotations.react
import slinky.web.html.{className, div}


@react class ReportView extends StatelessComponent {
  case class Props(name: String)

  var ws: Option[BacktestWebSocket] = None

  override def componentDidMount() = {
    ws = Some(new BacktestWebSocket(s"ws://${dom.window.location.host}/backtest/${BacktestConfig.strategy}/ws"))
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

