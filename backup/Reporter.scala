package core

import akka.actor.Actor
import core.OMS.PortfolioPair

//case class Balances(base: (String, Double), quote: (String, Double)) {
//  def trade(t: Trade): Balances = t match {
//    case Trade(_, _, size, price, Buy()) =>
//      Balances((base._1, base._2 + size), (quote._1, quote._2 - (size * price)))
//    case Trade(_, _, size, price, Sell()) =>
//      Balances((base._1, base._2 - size), (quote._1, quote._2 + (size * price)))
//  }
//}
//
//object Balances {
//  def empty(p: (String, String)): Balances = Balances((p._1, 0), (p._2, 0))
//}

case class PortfolioPoint(time: Long,
                          price: Double,
                          pair: PortfolioPair) {
  def value: Double = pair.quote.amount + (pair.base.amount * price)
}

class Reporter extends Actor {
  override def receive: Receive = {
    case ms: MarketState =>
    case trade: Trade =>
  }
}
