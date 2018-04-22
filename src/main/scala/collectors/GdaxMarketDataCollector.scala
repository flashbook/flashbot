package collectors

import akka.actor.{Actor, Props}
import info.bitrich.xchangestream.core.{StreamingExchange, StreamingExchangeFactory}
import info.bitrich.xchangestream.gdax.{GDAXStreamingExchange, GDAXStreamingMarketDataService}
import org.knowm.xchange.currency.CurrencyPair

class GdaxMarketDataCollector extends Actor {
  override def receive: Receive = ???
}

object GdaxMarketDataCollector {
  val ex: StreamingExchange = StreamingExchangeFactory.INSTANCE
    .createExchange(classOf[GDAXStreamingExchange].getName)

  ex.connect().blockingAwait()
  ex.getStreamingMarketDataService
    .getOrderBook(CurrencyPair.BTC_USD)
//    .subscribe()
}
