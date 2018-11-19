package io.flashbook.flashbot

import scala.util.matching.Regex

package object util {

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
