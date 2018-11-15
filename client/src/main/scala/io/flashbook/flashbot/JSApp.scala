package io.flashbook.flashbot

import java.net.URI

import io.circe.Json
import io.circe.parser._
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

  def paramsJson: Json = {
    val uri = new URI(dom.window.location.href)
    val kvs: Seq[String] = uri.getQuery.split("&")
    val body = kvs.map(kvStr => kvStr.split("=").toList match {
      case k :: v :: Nil => s""" "$k": $v """
    }).mkString(", ")
    val jsonStr = s"{$body}"
    println(jsonStr)
    parse(jsonStr).right.get
  }

  def main(args: Array[String]): Unit = {
    val root = Option(dom.document.getElementById("report"))
    val reportView = ReportView(name = BacktestConfig.strategy, params = paramsJson)
    ReactDOM.render(reportView, root.get)
  }
}
