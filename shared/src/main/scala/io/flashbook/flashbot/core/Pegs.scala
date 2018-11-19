package io.flashbook.flashbot.core

class Pegs(val pegs: Set[(String, String)]) extends AnyVal {
  def of(asset: String): Set[String] = of(Set(asset))

  def of(assets: Set[String]): Set[String] = {
    val pegMap = pegs.flatMap { case (a, b) => Set((a, b), (b, a))}.toMap
  }

  def without(assets: Set[String]): Pegs  = ???
}

object Pegs {
  implicit def pegs(set: Set[(String, String)]): Pegs = new Pegs(set)

  def default: Pegs = Set(
    ("usd", "usdt"),
    ("usd", "bitusd"),
    ("btc", "xbtusd")
  )
}
