package core

import core.Order.{Fill, Side}
import io.circe.Json
import io.circe.generic.auto._
import java.util.UUID.randomUUID

object Exchange {
  final case class ExchangeConfig(`class`: String, params: Json)
}

abstract class Exchange {

  def makerFee: Double
  def takerFee: Double

  // API requests submitted to the exchange are fire-and-forget, hence the Unit return type
  def order(req: OrderRequest): Unit
  def cancel(id: String, pair: Pair): Unit

  def baseAssetPrecision(pair: Pair): Int
  def quoteAssetPrecision(pair: Pair): Int
  def lotSize(pair: Pair): Option[Double] = None

  def useFundsForMarketBuys: Boolean = false

  var tick: () => Unit = () => {
    throw new RuntimeException("The default tick function should never be called")
  }
  def setTickFn(fn: () => Unit): Unit = {
    tick = fn
  }

  private var fills = Seq.empty[Fill]
  def fill(f: Fill): Unit = fills :+= f

  private var events = Seq.empty[OrderEvent]
  def event(e: OrderEvent): Unit = events :+= e

  /**
    * A function that returns user data by the exchange in its current state for the given
    * trading session.
    */
  def collect(session: TradingSession,
              data: Option[MarketData]): (Seq[Fill], Seq[OrderEvent]) = {
    val ret = (fills, events)
    fills = Seq.empty
    events = Seq.empty
    ret
  }

  def genOrderId: String = randomUUID.toString
}

sealed trait OrderRequest {
  val clientOid: String
  val side: Side
  val product: Pair
}

final case class LimitOrderRequest(clientOid: String,
                                   side: Side,
                                   product: Pair,
                                   size: Double,
                                   price: Double,
                                   postOnly: Boolean) extends OrderRequest

final case class MarketOrderRequest(clientOid: String,
                                    side: Side,
                                    product: Pair,
                                    size: Option[Double],
                                    funds: Option[Double]) extends OrderRequest

