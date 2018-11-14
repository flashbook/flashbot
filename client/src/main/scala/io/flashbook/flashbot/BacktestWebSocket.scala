package io.flashbook.flashbot

import io.circe.Printer
import io.flashbook.flashbot.BacktestParams._
import org.scalajs.dom.{Event, WebSocket}

class BacktestWebSocket(url: String) extends WebSocket(url) {
  onopen = (e: Event) => {
    val msg: String = paramsEn(StratParams("yo")).pretty(Printer.noSpaces)
    send(msg)
  }

  onmessage = msg => {
    println(msg)
  }
}


