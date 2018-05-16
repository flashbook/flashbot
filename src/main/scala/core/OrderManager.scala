package core

import akka.stream.scaladsl.Flow
import core.Action._

import scala.concurrent.Future


object OrderManager {
}

//
//class OrderManager {
//  import OrderManager._
//
//  def emitUserData(data: MarketData): Seq[UserData] = {
//    List()
//  }
//
//  def submitAction(action: OrderAction): Future[ActionResponse] = action match {
//    case PostMarketOrder(id, targetId, pair, side, percent) =>
//    case PostLimitOrder(id, targetId, pair, side, percent, price) =>
//    case CancelLimitOrder(id, targetId, pair) =>
//  }
//}
