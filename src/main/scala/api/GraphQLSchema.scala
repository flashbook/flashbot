package api

import core._
import core.TradingEngine.{BacktestQuery, Ping, Pong, Report}
import sangria.schema._
import sangria.macros.derive._
import sangria.marshalling.FromInput
import sangria.marshalling.circe._

object GraphQLSchema {
  def build(): Schema[UserCtx, Unit] = {

    val TimeRangeInputType = deriveInputObjectType[TimeRange]()
    val TimeRangeType = deriveObjectType[UserCtx, TimeRange]()

    val TradeType = deriveObjectType[UserCtx, Trade]()

    implicit val PricePointType: ObjectType[Unit, PricePoint] =
      deriveObjectType[Unit, PricePoint]()

    case class AccountBalances(currency: String, balances: Vector[PricePoint])
    implicit val AccountBalancesType: ObjectType[UserCtx, AccountBalances] =
      deriveObjectType[UserCtx, AccountBalances]()

    case class ExchangeBalances(exchange: String, accounts: List[AccountBalances])
    val ExchangeBalancesType = deriveObjectType[UserCtx, ExchangeBalances]()

    val ReportType = ObjectType("Report",
      "A full trading session report",
      fields[UserCtx, Report](
        Field("strategy", StringType, resolve = c => c.value.strategy),
        Field("params", StringType, resolve = c => c.value.params),
        Field("time_range", TimeRangeType, resolve = c => c.value.timeRange),
        Field("done", BooleanType, resolve = c => c.value.done),
        Field("trades", ListType(TradeType), resolve = c => c.value.trades),
        Field("balances", ListType(ExchangeBalancesType), resolve = c =>
          c.value.balances.toList.map { case (exchange, accounts) =>
            ExchangeBalances(exchange, accounts.toList.map { case (acc, balances) =>
                AccountBalances(acc, balances)
            })
          })
      ))

    val StrategyNameArg = Argument("strategy", StringType,
      description = "The strategy name")

    val StrategyParamsArg = Argument("params", StringType,
      description = "JSON string of the strategy parameters")

//    val TimeRangeArg = Argument("time_range", TimeRangeInputType,
//      description = "Time range in micros")

    val FromArg = Argument("from", LongType)
    val ToArg = Argument("to", LongType)

    val QueryType = ObjectType("Query", fields[UserCtx, Unit](
      /**
        * Ping the trading engine. Should resolve to a pong.
        */
      Field("ping", StringType, resolve = c => c.ctx.ping),

      /**
        * A backtest query runs a strategy on historical data and resolves to the full trading
        * session report. The entire report must be sent back because it's not stored anywhere
        * by the server after the backtest completes.
        */
      Field("backtest", ReportType,
        arguments = StrategyNameArg :: StrategyParamsArg :: FromArg :: ToArg :: Nil,
        resolve = c => c.ctx.request[Report](BacktestQuery(
          c.arg(StrategyNameArg),
          c.arg(StrategyParamsArg),
          TimeRange(c.arg(FromArg), c.arg(ToArg))
        )))
    ))

//    val MutationType = ObjectType("Mutation", fields[UserCtx, Unit](
//      /**
//        * A bot is an immutable record describing a strategy, along with it's configuration
//        * parameters. It can be turned on and off for live trading. It is also continuously
//        * running a paper trading session.
//        */
//      Field("createBot", ???, resolve = c => ???)
//    ))

    Schema(QueryType)
  }
}
