package io.flashbook.flashbot

import io.flashbook.flashbot.core.Instrument.CurrencyPair

package object implicits {
  implicit class CurrencyPairOps(product: String) {
    def pair: CurrencyPair = CurrencyPair(product)
  }
}
