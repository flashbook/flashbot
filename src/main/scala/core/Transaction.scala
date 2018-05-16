package core

// In the future we may want to support other types of transactions, such as account transfers.
sealed trait Transaction
case class TradeTx(id: String,
                   exchange: String,
                   time: Long,
                   makerOrder: String,
                   takerOrder: String,
                   price: Double,
                   size: Double) extends Transaction
