package io.flashbook.flashbot

import scala.util.matching.Regex
import io.flashbook.flashbot.core.Pair

package object util {

  def parseProductId(str: String): Pair = {
    var list = str.split("-")
    if (list.length == 1)
      list = str.split("_")
    Pair(list(0), list(1))
  }

  val longVal: Regex = raw"^([0-9]+)$$".r
  val rmDot: Regex = raw"^([0-9]+)\.0+$$".r
  val doubleVal: Regex = raw"^([0-9]+)(\.[0-9]*[1-9])0*$$".r

  /**
    * Removes the trailing zeroes (and the period, if applicable) from the string representation
    * of a number.
    */
  def stripTrailingZeroes(d: String): String = d match {
    case longVal(v: String) => v
    case rmDot(v: String) => v
    case doubleVal(a: String, b: String) => a + b
  }

}
