package io.flashbook.flashbot

import slinky.core.{ExternalComponent, StatelessComponent}
import slinky.core.annotations.react
import slinky.web.html.div

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("react-jsonschema-form", JSImport.Default)
object SchemaForm extends js.Object

@react object Form extends ExternalComponent {
  case class Props(schema: String)
  override val component = SchemaForm
}

