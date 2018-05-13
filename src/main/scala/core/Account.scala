package core

import akka.stream.scaladsl.Balance

case class Account(address: Option[String],
                   currency: String,
                   exchange: String)
