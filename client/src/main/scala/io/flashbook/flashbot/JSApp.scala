package io.flashbook.flashbot

import org.scalajs.dom
import slinky.web.ReactDOM

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native
@JSGlobal
object BacktestConfig extends js.Object {
  def strategy: String = js.native
}

object JSApp {
  def main(args: Array[String]): Unit = {
    val root = Option(dom.document.getElementById("report"))
    val reportView = ReportView(name = BacktestConfig.strategy)
    ReactDOM.render(reportView, root.get)
  }
}
