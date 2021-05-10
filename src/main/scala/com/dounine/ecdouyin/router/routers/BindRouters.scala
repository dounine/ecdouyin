package com.dounine.ecdouyin.router.routers

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpRequest,
  HttpResponse
}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.{
  ExceptionHandler,
  RejectionHandler,
  RequestContext,
  Route,
  RouteResult
}
import akka.pattern.AskTimeoutException
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration._

object BindRouters extends SuportRouter {

  implicit def rejectionHandler =
    RejectionHandler.default
      .mapRejectionResponse {
        case res @ HttpResponse(code, a, ent: HttpEntity.Strict, url) =>
          if (code.intValue() == 404) {
            res.withEntity(
              HttpEntity(
                ContentTypes.`application/json`,
                s"""{"code":"fail","msg": "resource not found"}"""
              )
            )
          } else {
            val message = ent.data.utf8String.replaceAll("\"", """\"""")
            res.withEntity(
              HttpEntity(
                ContentTypes.`application/json`,
                s"""{"code":"fail","msg": "$message"}"""
              )
            )
          }
        case x => x
      }

  implicit def exceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case timeout: AskTimeoutException =>
        fail(timeout.getMessage)
      case e: Exception =>
        fail(e.getMessage)
    }

  val requestTimeout = ConfigFactory
    .load()
    .getDuration("akka.http.server.request-timeout")
    .toMillis

  def apply(system: ActorSystem[_]): RequestContext => Future[RouteResult] = {
    Route.seal(
      /**
        * all request default timeout
        * child request can again use withRequestTimeout, Level child > parent
        */
      withRequestTimeout(
        requestTimeout.millis,
        (_: HttpRequest) => timeoutResponse
      )(
        concat(
          new HealthRouter(system).route,
          new WebsocketRouter(system).route,
          new FileRouter(system).route,
          new OrderRouter(system).route
        )
      )
    )
  }

}
