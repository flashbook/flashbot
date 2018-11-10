package io.flashbook.flashbot

import org.scalajs.dom
import slinky.core._
import slinky.core.annotations.react
import slinky.web.ReactDOM
import slinky.web.html._

import scala.concurrent.duration._

object JSApp {

  @react class FooComp extends StatelessComponent {
    case class Props(name: String)
    def render() = h1(s"Hello ${props.name}")
  }

  @react class AnotherClass extends StatelessComponent {
    case class Props(name: String)
    def render() = h2(props.name)
  }

  def enhance[T](C: ReactComponentClass[T]): ReactComponentClass[T] = C

  val FooClass = enhance(AnotherClass)

  def main(args: Array[String]): Unit = {
    val root = dom.document.getElementById("report")
    val yo = FooComp(name = "haha yeeee!!!!")
    val text = "hi"
    val ho2 = AnotherClass(name = text)
    ReactDOM.render(ho2, root)
  }

}
