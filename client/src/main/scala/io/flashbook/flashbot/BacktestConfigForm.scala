package io.flashbook.flashbot

import slinky.core.{ExternalComponent, StatelessComponent}
import slinky.core.annotations.react
import slinky.web.html.{className, div}

import scala.scalajs.js
import scalajs.js.annotation._

@react class BacktestConfigForm extends StatelessComponent {
  case class Props(foo: String)

  val jsDict = js.Dictionary(
    "title" -> "todo",
    "type" -> "object",
    "required" -> js.Array("title"),
    "properties" -> js.Dictionary(
      "title" -> js.Dictionary("type" -> "string", "title" -> "HI")
    )
  )

  def render() = div(className := "BacktestConfigForm")(
    div(className := "hi")(
      "yo",
      Form(schema = props.foo)
    )
  )
}
