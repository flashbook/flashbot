package core

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.stream.scaladsl.Flow
import bots.OrderTarget

import scala.collection.immutable.{HashMap, Queue}

import java.util.UUID.randomUUID

object OMS {

  implicit val system: ActorSystem = ActorSystem("oms")
  val log: LoggingAdapter = Logging.getLogger(system, this)

  // State that represents the funds available for each currency.
  var balances: Map[String, Double] = HashMap()

  // When order targets come in, they are actually serialized into a queue of order
  // transactions per bot id.
  var transactions: Map[String, TxQueue] = HashMap()

  val targets: TargetManager = new TargetManager

  val ids: IdManager = new IdManager()

  sealed trait PairRole
  case object Base extends PairRole
  case object Quote extends PairRole

  case class PortfolioPair(product: (String, String), amounts: (Double, Double), incr: Double) {
    case class CurrencyAmount(name: String, amount: Double)
    def base: CurrencyAmount = CurrencyAmount(product._1, amounts._1)
    def quote: CurrencyAmount = CurrencyAmount(product._2, amounts._2)

    def amount(role: PairRole, ratio: Double): Double = role match {
      case Base => BigDecimal(base.amount * ratio)
        .setScale(sizeIncr(base.name), BigDecimal.RoundingMode.FLOOR)
        .toDouble
      case Quote => BigDecimal(quote.amount * ratio)
        .setScale(sizeIncr(quote.name), BigDecimal.RoundingMode.FLOOR)
        .toDouble
    }
  }

  def quoteIncr(currency: String): Int = if (currency == "BTC") 5 else 2
  def sizeIncr(currency: String): Int = if (currency == "USD") 2 else 6

  def pair(p: (String, String)): PortfolioPair =
    PortfolioPair(p, (balances(p._1), balances(p._2)), if (p._2 == "BTC") 0.00001 else 0.01)

  def closeTxForOrderId(id: String): Unit = transactions.foreach {
    case (botId, TxQueue(Some(tx), _)) =>
      if (ids.actualIdForTargetId(tx.targetId) == id) {
        // Close the active tx
        transactions = transactions + (botId -> transactions(botId).closeActive)
      }
    case _ =>
  }

  def flow(exchange: Exchange):
  Flow[(MarketState, Seq[OrderTarget]), (MarketState, PortfolioPair), NotUsed] = {

    balances = exchange.accounts

    Flow[(MarketState, Seq[OrderTarget])].map {
      case (ms, ts) =>
        val (fills, events) = exchange.update(ms)

        // Loop through all events emitted from the exchange in response to market update.
        events.foreach {
          case Received(id, _, clientOid, _) =>
            ids.receivedOrder(clientOid.get, id)

          case Open(id, p, _, _, _) =>
            // If a limit order just opened, close the appropriate PostLimitOrder transaction.
            closeTxForOrderId(id)

          case Done(id, p, _, reason, _, _) =>
            reason match {
              case Canceled =>
                // If order was canceled, see if there is a CancelLimitOrder transaction that
                // we need to close in response.
                closeTxForOrderId(id)

              case Filled =>
                // If a market order was filled, we close the associated transaction.
                closeTxForOrderId(id)
            }
            ids.orderComplete(id)
        }

        // This is where we keep our balances up to date. Fees get processed here.
        fills.foreach {
          case Fill(_, _, fee, (base, quote), price, size, _, _, Buy()) =>
            balances = balances + (
              base -> (balances(base) + size),
              quote -> (balances(quote) - (price * size * (1 + fee)))
            )
          case Fill(_, _, fee, (base, quote), price, size, _, _, Sell()) =>
            balances = balances + (
              base -> (balances(base) - size),
              quote -> (balances(quote) + (price * size * (1 - fee)))
            )
        }

        // Send each OrderTarget to the TargetManager and enqueue the transactions emitted by it
        // to the corresponding bot tx queue.
        ts.foreach { t =>
          transactions = transactions +
            (t.bot.id -> transactions.getOrElse(t.bot.id, TxQueue.empty).enqueue(targets.step(t)))
        }

        // Keep the transactions flowing.
        transactions.foreach {
          case (botId, TxQueue(None, tx +: txs)) =>
            // If there is a queued up transaction, but no active one, then dequeue and start it.
            transactions = transactions + (botId -> TxQueue(Some(tx), txs))
            tx match {
              case PostMarketOrder(txid, targetId, p, side, ratio) =>
                ids.initOrder(targetId, txid)
                exchange.order(MarketOrderRequest(txid, side, p,
                  if (side == Buy()) None else Some(pair(p).amount(Quote, ratio)),
                  if (side == Sell()) None else Some(pair(p).amount(Base, ratio))))

              case PostLimitOrder(txid, targetId, p, side, ratio, price) =>
                ids.initOrder(targetId, txid)
                exchange.order(LimitOrderRequest(txid, side, p,
                  BigDecimal(price).setScale(quoteIncr(p._2),
                    if (side == Buy()) BigDecimal.RoundingMode.CEILING
                    else BigDecimal.RoundingMode.FLOOR).doubleValue,
                  pair(p).amount(if (side == Buy()) Quote else Base, ratio)))

              case CancelLimitOrder(txid, targetId, p) =>
                exchange.cancel(ids.actualIdForTargetId(targetId))
            }
          case _ => // Do nothing otherwise
        }


        (ms, pair(ms.product))
    }
  }
}

class TargetManager {

  type MarketMap = Map[(String, String), MarketTarget]

  case class LimitTarget(price: Double, ratio: Double)

  case class MarketTarget(ratio: Double, book: Map[String, LimitTarget])
  val emptyMT = MarketTarget(0, HashMap())

  var markets: MarketMap = HashMap()

  def withLT(markets: MarketMap, p: (String, String), id: String,
             price: Double, ratio: Double): MarketMap = {
    val mt = markets.getOrElse(p, emptyMT)
    markets + (p -> mt.copy(book = mt.book + (id -> LimitTarget(price, ratio))))
  }

  def withoutLT(markets: MarketMap, p: (String, String), id: String): MarketMap = {
    val mt = markets.getOrElse(p, emptyMT)
    markets + (p -> mt.copy(book = mt.book - id))
  }

  def postLimitOrderTx(id: String, p: (String, String),
                       price: Double, ratio: Double): PostLimitOrder =
    PostLimitOrder(randomUUID.toString, id, p, if (ratio > 0) Buy() else Sell(),
      Math.abs(ratio), price)

  def step(t: OrderTarget): List[OrderTx] = t match {
    case OrderTarget(ratio, p, None, _) =>
      // Market order target
      val curr = markets.getOrElse(p, emptyMT).ratio
      val diff = ratio - curr
      if (diff != 0)
        List(PostMarketOrder(randomUUID.toString, t.id, p,
          if (diff > 0) Buy() else Sell(), Math.abs(diff)))
      else List()

    case OrderTarget(ratio, p, Some((_, price)), bot) =>
      // Limit order target
      var txs: List[OrderTx] = List()
      val currMT = markets.getOrElse(p, emptyMT)
      (markets, txs) = (currMT.book.get(t.id), (price, ratio)) match {
        case (None, (_, 0)) =>
          // If there is no current limit target, and the next one's ratio is 0, ignore.
          (markets, List())

        case (None, (_, _)) =>
          // In every other case where there is no current limit target, post a limit order.
          (withLT(markets, p, t.id, price, ratio),
            List(postLimitOrderTx(t.id, p, price, ratio)))

        case (Some(LimitTarget(currPrice, currRatio)), (_, 0)) =>
          // If there is a current limit target, but the new one has a 0 ratio,
          // cancel the limit order.
          (withoutLT(markets, p, t.id), List(CancelLimitOrder(randomUUID.toString, t.id, p)))

        case (Some(LimitTarget(currPrice, currRatio)), (_, _)) =>
          // Otherwise we have both a current limit target and a new, non-zero one.
          if (currPrice == price && currRatio == ratio)
            // Ignore if both the price and ratio are identical.
            (markets, List())
          else
            // Otherwise delete the current limit order and post a new one after that.
            (withLT(markets, p, t.id, price, ratio),
              List(CancelLimitOrder(randomUUID.toString, t.id, p),
                postLimitOrderTx(t.id, p, price, ratio)))
      }
      txs
  }
}

// Manages the relationships of the three types of order ids in our system:
// Actual ids, client ids, target ids.
class IdManager {
  var clientToTarget: Map[String, String] = HashMap()
  var targetToActual: Map[String, String] = HashMap()
  var actualToTarget: Map[String, String] = HashMap()

  def initOrder(targetId: String, clientId: String): Unit = {
    clientToTarget = clientToTarget + (clientId -> targetId)
  }

  def receivedOrder(clientId: String, actualId: String): Unit = {
    targetToActual = targetToActual + (clientToTarget(clientId) -> actualId)
    actualToTarget = actualToTarget + (actualId -> clientToTarget(clientId))
    clientToTarget = clientToTarget - clientId
  }

  def orderComplete(actualId: String): Unit = {
    targetToActual = targetToActual - actualToTarget(actualId)
    actualToTarget = actualToTarget - actualId
  }

  def actualIdForTargetId(targetId: String): String = targetToActual(targetId)
}

sealed trait OrderTx {
  val id: String
  val targetId: String
  val p: (String, String)
}
case class PostMarketOrder(id: String, targetId: String, p: (String, String), side: Side, ratio: Double) extends OrderTx
case class PostLimitOrder(id: String, targetId: String, p: (String, String), side: Side, ratio: Double, price: Double) extends OrderTx
case class CancelLimitOrder(id: String, targetId: String, p: (String, String)) extends OrderTx

case class TxQueue(active: Option[OrderTx], queue: Queue[OrderTx]) {
  def enqueue(txs: List[OrderTx]): TxQueue = copy(queue = queue.enqueue(txs))
  def closeActive: TxQueue = active match {
    case Some(_) => copy(active = None)
  }
}
object TxQueue {
  def empty: TxQueue = TxQueue(None, Queue.empty)
}

