package com.dounine.ecdouyin.router.routers

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import com.dounine.ecdouyin.service.UserService
import com.dounine.ecdouyin.tools.util.ServiceSingleton
import sangria.ast.Document
import sangria.execution._
import sangria.parser.QueryParser
import spray.json.{JsObject, JsString, JsValue}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
object GraphQLServer extends SuportRouter {

//  def endpoint(requestJSON: JsValue)(implicit ec: ExecutionContext): Route = {
//    val JsObject(fields) = requestJSON
//    val JsString(query) = fields("query")
//    val operation = fields.get("operationName") collect {
//      case JsString(op) => op
//    }
//    val vars = fields.get("variables") match {
//      case Some(obj: JsObject) => obj
//      case _                   => JsObject.empty
//    }
//    QueryParser.parse(query) match {
//      case Failure(exception) => complete(failMsg(exception.getMessage))
//      case Success(queryAst) =>
//        complete(executeGraphqlQuery(queryAst, operation, vars))
//    }
//  }

//  def executeGraphqlQuery(
//      query: Document,
//      op: Option[String],
//      vars: JsObject
//  )(implicit ec: ExecutionContext) = {
//    Executor
//      .execute(
//        SchemaDef.UserSchema,
//        query,
//        ServiceSingleton.get(classOf[UserService]),
//        operationName = op,
//        variables = vars
//      )
//      .map(i => OK(i))
//      .recover {
//        case error: QueryAnalysisError => error.resolveError
//        case error: ErrorWithResolver  => error.resolveError
//      }
//  }

}
