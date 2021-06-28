package com.dounine.ecdouyin.router.routers

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import com.dounine.ecdouyin.service.UserService
import com.dounine.ecdouyin.tools.util.ServiceSingleton
import sangria.execution.deferred.DeferredResolver
import sangria.execution.{ErrorWithResolver, Executor, QueryAnalysisError}
import sangria.marshalling.circe._
import sangria.slowlog.SlowLog

import scala.util.{Failure, Success}

// This is the trait that makes `graphQLPlayground and prepareGraphQLRequest` available
import sangria.http.akka.circe.CirceHttpSupport

class GraphqlRouter(system: ActorSystem[_]) extends CirceHttpSupport {
  implicit val ec = system.executionContext

  val route: Route =
    optionalHeaderValueByName("X-Apollo-Tracing") { tracing =>
      path("graphql") {
        graphQLPlayground ~
          prepareGraphQLRequest {
            case Success(req) =>
              val middleware =
                if (tracing.isDefined) SlowLog.apolloTracing :: Nil else Nil
//              val deferredResolver = DeferredResolver.fetchers(SchemaDefinition.characters)
              val graphQLResponse = Executor
                .execute(
                  schema = SchemaDef.UserSchema,
                  queryAst = req.query,
                  userContext = ServiceSingleton.get(classOf[UserService]),
                  variables = req.variables,
                  operationName = req.operationName,
                  middleware = middleware
                  //                deferredResolver = deferredResolver
                )
                .map(OK -> _)
                .recover {
                  case error: QueryAnalysisError =>
                    BadRequest -> error.resolveError
                  case error: ErrorWithResolver =>
                    InternalServerError -> error.resolveError
                }
              complete(graphQLResponse)
            case Failure(preparationError) =>
              complete(BadRequest, formatError(preparationError))
          }
      }
    }
}
