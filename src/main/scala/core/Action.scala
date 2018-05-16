package core

import core.Order.Side

import scala.collection.immutable.Queue

trait Action {
  def targetId: String
}

object Action {
  trait OrderAction extends Action
  case class PostMarketOrder(id: String, targetId: String, pair: Pair, side: Side,
                             percent: Percent) extends OrderAction
  case class PostLimitOrder(id: String, targetId: String, pair: Pair, side: Side,
                            percent: Percent, price: Double) extends OrderAction
  case class CancelLimitOrder(id: String, targetId: String, pair: Pair) extends OrderAction

  trait ActionResponse
  case object Ok extends ActionResponse
  // TODO: Add more specific failures and handle them (such as rate limit retries)
  case object Fail extends ActionResponse

  case class ActionQueue(active: Option[Action] = None, queue: Queue[Action] = Queue.empty) {
    def enqueue(action: Action): ActionQueue = copy(queue = queue.enqueue(action))
    def closeActive: ActionQueue = active match {
      case Some(_) => copy(active = None)
    }
  }
}
