package core

import core.Action._

import scala.concurrent.Future


class OrderManager {

  def step(data: MarketData): Seq[Transaction] = {
    List()
  }

  def submitAction(action: OrderAction): Future[ActionResponse] = action match {
    case PostMarketOrder(id, targetId, pair, side, percent) =>
    case PostLimitOrder(id, targetId, pair, side, percent, price) =>
    case CancelLimitOrder(id, targetId, pair) =>
  }
}
