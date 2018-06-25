package api

import core._
import core.TradingEngine._
import io.circe.{Json, JsonNumber}
import io.circe.parser._
import io.circe.syntax._
import sangria.schema._
import sangria.macros.derive._
import sangria.marshalling.FromInput
import sangria.marshalling.circe._

object GraphQLSchema {
  def build(): Schema[UserCtx, Unit] = {

    val TimeRangeInputType = deriveInputObjectType[TimeRange]()
    val TimeRangeType = deriveObjectType[UserCtx, TimeRange]()

    val TradeType = deriveObjectType[UserCtx, TradeEvent]()

    implicit val PricePointType: ObjectType[Unit, PricePoint] =
      deriveObjectType[Unit, PricePoint]()

    case class AccountBalances(currency: String, balances: Vector[PricePoint])
    implicit val AccountBalancesType: ObjectType[UserCtx, AccountBalances] =
      deriveObjectType[UserCtx, AccountBalances]()

    case class ExchangeBalances(exchange: String, accounts: List[AccountBalances])
    val ExchangeBalancesType = deriveObjectType[UserCtx, ExchangeBalances]()

//    case class BalanceSeries(name: String, data: Vector[Double])
//    val BalanceSeriesType = deriveObjectType[UserCtx, BalanceSeries]()
//
//    case class PriceSeries(name: String, data: Vector[Double])
//    val PriceSeriesType = deriveObjectType[UserCtx, BalanceSeries]()

    case class TimeSeries(name: String, data: Vector[Option[Double]])
    val TimeSeriesType = deriveObjectType[UserCtx, TimeSeries]()

    val ReportType = ObjectType("Report",
      "A trading session report",
      fields[UserCtx, Report](
        Field("strategy", StringType, resolve = c => c.value.strategy),
        Field("params", StringType, resolve = c => c.value.params),
        Field("time_range", TimeRangeType, resolve = c => c.value.timeRange),
        Field("done", BooleanType, resolve = c => c.value.done),
        Field("trades", ListType(TradeType), resolve = c => c.value.trades),

        Field("time_series", ListType(TimeSeriesType),
          resolve = c => c.value.timeSeries.map {
            case (k, v) => TimeSeries(k, v.values.map(Some(_)))
          }.toSeq)

//        Field("balances", ListType(BalanceSeriesType), resolve = c =>
//          c.value.balances.map {
//            case (name, points) =>
//              BalanceSeries(name, points.map(_.balance))}),
//
//        Field("prices", ListType(PriceSeriesType), resolve = c =>
//          c.value.prices.map {
//            case (name, points) =>
//              PriceSeries(name, points.map(_.price))
//          }
//        )
      ))

    val StrategyNameArg = Argument("strategy", StringType,
      description = "The strategy name")

    val StrategyParamsArg = Argument("params", StringType,
      description = "JSON string of the strategy parameters")

//    val TimeRangeArg = Argument("time_range", TimeRangeInputType,
//      description = "Time range in micros")

    val FromArg = Argument("from", LongType)
    val ToArg = Argument("to", LongType)

    val BalancesArg = Argument("balances", StringType)

    val MakerFeeArg = Argument("maker_fee", OptionInputType(FloatType))
    val TakerFeeArg = Argument("taker_fee", OptionInputType(FloatType))

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
        arguments = StrategyNameArg :: StrategyParamsArg :: FromArg :: ToArg :: BalancesArg ::
          MakerFeeArg :: TakerFeeArg :: Nil,
        resolve = c => c.ctx.request[Report](BacktestQuery(
          c.arg(StrategyNameArg),
          c.arg(StrategyParamsArg),
          TimeRange(c.arg(FromArg), c.arg(ToArg)),
          c.arg(BalancesArg),
          c.arg(MakerFeeArg),
          c.arg(TakerFeeArg)
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
