package core

sealed trait OrderEvent
case class OrderOpen(exchange: String, order: Order) extends OrderEvent
case class OrderCanceled(id: String, exchange: String) extends OrderEvent
