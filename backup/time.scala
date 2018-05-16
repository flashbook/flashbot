package core

sealed trait TimeUnit
case class Millis() extends TimeUnit
case class Seconds() extends TimeUnit
case class Minutes() extends TimeUnit
case class Hours() extends TimeUnit
case class Days() extends TimeUnit
case class Weeks() extends TimeUnit

case class Duration(n: Long, tu: TimeUnit)
