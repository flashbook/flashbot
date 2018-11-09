package io.flashbook.flashbot

import org.scalajs.dom
import slinky.core._
import slinky.core.annotations.react
import slinky.web.ReactDOM
import slinky.web.html._


object JSApp {

  @react class FooComp extends StatelessComponent {
    case class Props(name: String)
    def render() = h1(s"Hello ${props.name}")
  }

  def main(args: Array[String]): Unit = {
    val root = dom.document.getElementById("scalajsShoutOut")
    val yo = FooComp(name = "haha yeeee!!!!")
    ReactDOM.render(yo, root)
  }

}
