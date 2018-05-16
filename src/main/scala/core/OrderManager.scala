package core

import core.Action._

import scala.concurrent.Future


object OrderManager {
  sealed trait UserData
  case class UserTx(transaction: Transaction) extends UserData
  case class UserOrderEvent(event: OrderEvent) extends UserData
}

class OrderManager {
  import OrderManager._

  def emitUserData(data: MarketData): Seq[UserData] = {
    List()
  }

  def submitAction(action: OrderAction): Future[ActionResponse] = action match {
    case PostMarketOrder(id, targetId, pair, side, percent) =>
    case PostLimitOrder(id, targetId, pair, side, percent, price) =>
    case CancelLimitOrder(id, targetId, pair) =>
  }
}
