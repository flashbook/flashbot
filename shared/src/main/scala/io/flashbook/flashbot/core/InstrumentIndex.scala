package io.flashbook.flashbot.core

import io.flashbook.flashbot.core.Instrument.CurrencyPair

class InstrumentIndex(val instruments: Map[String, Map[String, Instrument]]) extends AnyVal {
  def get(exchange: String, symbol: String): Option[Instrument] = for {
    ex <- instruments.get(exchange)
    inst <- ex.get(symbol)
  } yield inst

  def apply(exchange: String, symbol: String): Instrument =
    get(exchange, symbol).getOrElse(CurrencyPair(symbol))
}

object InstrumentIndex {
  implicit def instrumentIndex(instruments: Map[String, Map[String, Instrument]]): InstrumentIndex
    = InstrumentIndex(instruments)
}
