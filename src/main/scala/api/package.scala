import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes._
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, StandardRoute}
import akka.stream.Materializer
import io.circe.Json
import io.circe.optics.JsonPath._
import io.circe.parser._
import sangria.parser.QueryParser
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import sangria.ast.Document
import sangria.execution.Executor
import sangria.schema.Schema
import sangria.marshalling.circe._
import api.GraphQLRequestUnmarshaller._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

package object api {
  implicit val schema: Schema[UserCtx, Unit] = GraphQLSchema.build()

  def routes(engine: ActorRef)
            (implicit system: ActorSystem,
             mat: Materializer,
             ec: ExecutionContext): Route = cors() {
    get {
      /**
        * Serve the GraphiQL in-browser GraphQL IDE from the "/graphiql" endpoint.
        */
      pathPrefix("graphiql") {
        getFromResourceDirectory("public/graphiql")
      }

    } ~ path("graphql") {
      get {
        parameters('query, 'operationName.?, 'variables.?) {
          (query, operationName, variables) =>

            QueryParser.parse(query) match {
              case Success(ast) =>
                variables.map(parse) match {
                  case Some(Right(vars)) => executeGraphQL(engine, ast, operationName, vars)
                  case Some(Left(err)) => complete(BadRequest, ApiError.format(err))
                  case None => executeGraphQL(engine, ast, operationName, Json.obj())
                }

              case Failure(err) => complete(BadRequest, ApiError.format(err))
            }
        }
      } ~
      post {
        parameters('query.?, 'operationName.?, 'variables.?) {
          (queryParam, operationNameParam, variablesParam) =>

            entity(as[Json]) { body =>
              val query = queryParam orElse root.query.string.getOption(body)
              val operationName = operationNameParam orElse root.operationName.string.getOption(body)
              val variablesStr = variablesParam orElse root.variables.string.getOption(body)

              query.map(QueryParser.parse(_)) match {
                case Some(Success(ast)) => handleVars(engine, ast, operationName, variablesStr)
                case Some(Failure(err)) => complete(BadRequest, ApiError.format(err))
                case None => complete(BadRequest, ApiError.format("GraphQL query must be present"))
              }

            } ~
            entity(as[Document]) { document =>
              handleVars(engine, document, operationNameParam, variablesParam)
            }
        }
      }
    }
  }

  def handleVars(engine: ActorRef,
                 query: Document,
                 opName: Option[String],
                 variables: Option[String])
                (implicit sys: ActorSystem,
                 mat: Materializer,
                 ec: ExecutionContext): Route = {
    variables.map(parse) match {
      case Some(Left(err)) => complete(BadRequest, ApiError.format(err))
      case Some(Right(vars)) => executeGraphQL(engine, query, opName, vars)
      case None => executeGraphQL(engine, query, opName, Json.obj())
    }
  }

  def executeGraphQL(engine: ActorRef, query: Document, op: Option[String], vars: Json)
                    (implicit sys: ActorSystem,
                     mat: Materializer,
                     ec: ExecutionContext,
                     schema: Schema[UserCtx, Unit]): StandardRoute = {
    complete(Executor.execute(
      schema,
      query,
      operationName = op,
      variables = if (vars.isNull) Json.obj() else vars,
      userContext = new UserCtx(engine)
    ))
  }
}
