package core

trait Transaction

object Transaction {
  case class TradeTx(trade: Trade) extends Transaction
}
