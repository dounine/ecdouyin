package com.dounine.ecdouyin.router.routers

import akka.actor
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.model.{
  ContentType,
  HttpEntity,
  HttpResponse,
  MediaTypes
}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import akka.stream._
import com.dounine.ecdouyin.behaviors.order.OrderBase
import com.dounine.ecdouyin.model.models.{OrderModel, RouterModel}
import com.dounine.ecdouyin.model.types.router.ResponseCode
import com.dounine.ecdouyin.model.types.service.{MechinePayStatus, PayStatus}
import com.dounine.ecdouyin.service.{OrderService, UserService}
import com.dounine.ecdouyin.tools.util.{MD5Util, ServiceSingleton}
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import java.nio.file.{Files, Paths}
import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._
class OrderRouter(system: ActorSystem[_]) extends SuportRouter {

  private final val logger: Logger =
    LoggerFactory.getLogger(classOf[OrderRouter])
  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val actorSystem: actor.ActorSystem = materializer.system
  implicit val executionContext: ExecutionContextExecutor =
    materializer.executionContext

  val sharding = ClusterSharding(system)
  val orderService = ServiceSingleton.get(classOf[OrderService])
  val userService = ServiceSingleton.get(classOf[UserService])

  val route: Route =
    concat(
      get {
        path("order" / "info") {
          parameterMap {
            querys =>
              {
                logger.info(querys.logJson)
                val queryInfo = querys.toJson.jsonTo[OrderModel.Query]
                val result = userService
                  .info(queryInfo.apiKey)
                  .map {
                    case Some(value) => {
                      MD5Util.md5(
                        value.apiSecret + queryInfo.orderId
                          .getOrElse(queryInfo.outOrder.get)
                      ) == queryInfo.sign
                    }
                    case None => false
                  }
                  .flatMap {
                    case true =>
                      if (queryInfo.orderId.isDefined) {
                        orderService.infoOrder(queryInfo.orderId.get.toLong)
                      } else if (queryInfo.outOrder.isDefined) {
                        orderService.infoOutOrder(queryInfo.outOrder.get)
                      } else {
                        Future.failed(
                          new Exception("orderId or outOrder required one")
                        )
                      }
                    case false => Future.failed(new Exception("sign invalid"))
                  }
                onComplete(result) {
                  case Failure(exception) => fail(exception.getMessage)
                  case Success(value) => {
                    value match {
                      case Some(order) =>
                        ok(
                          Map(
                            "orderId" -> order.orderId.toString,
                            "outOrder" -> order.outOrder,
                            "account" -> order.account,
                            "platform" -> order.platform,
                            "money" -> order.money,
                            "status" -> order.status,
                            "margin" -> order.margin.toString,
                            "createTime" -> order.createTime
                          )
                        )
                      case None => fail("order not found")
                    }
                  }
                }
              }
          }
        }
      },
      post {
        path("order" / "recharge") {
          entity(as[OrderModel.Recharge]) {
            order =>
              {
                logger.info(order.logJson)
                val result = userService
                  .info(order.apiKey)
                  .flatMap {
                    case Some(userInfo) =>
                      if (
                        MD5Util.md5(
                          userInfo.apiSecret + order.account + order.money + order.platform + order.outOrder
                        ) != order.sign
                      ) {
                        Future.failed(new Exception("sign invalid"))
                      } else if (
                        userInfo.balance - BigDecimal(order.money) < 0
                      ) {
                        Future.successful(
                          complete(
                            Map(
                              "code" -> "fail",
                              "msg" -> "balance not enough",
                              "data" -> Map(
                                "balance" -> userInfo.balance,
                                "margin" -> userInfo.margin
                              )
                            )
                          )
                        )
                      } else {
                        sharding
                          .entityRefFor(
                            OrderBase.typeKey,
                            OrderBase.typeKey.name
                          )
                          .ask(
                            OrderBase.Create(
                              OrderModel.DbInfo(
                                orderId = 0,
                                outOrder = order.outOrder,
                                apiKey = order.apiKey,
                                account = order.account,
                                money = order.money.toInt,
                                volumn = order.money.toInt * 10,
                                margin = BigDecimal(order.money),
                                platform = order.platform,
                                status = PayStatus.normal,
                                mechineStatus = MechinePayStatus.normal,
                                payCount = 0,
                                createTime = LocalDateTime.now()
                              )
                            )
                          )(3.seconds)
                          .map {
                            case OrderBase.CreateOk(orderId) =>
                              ok(
                                Map(
                                  "orderId" -> orderId.toString,
                                  "outOrder" -> order.outOrder,
                                  "balance" -> (userInfo.balance - BigDecimal(
                                    order.money
                                  )),
                                  "margin" -> (userInfo.margin + BigDecimal(
                                    order.money
                                  ))
                                )
                              )
                            case OrderBase.CreateFail(msg) =>
                              throw new Exception(msg)
                          }
                      }
                    case None => Future.failed(new Exception("api not found"))
                  }
                onComplete(result) {
                  case Failure(exception) => fail(exception.getMessage)
                  case Success(value)     => value
                }
              }
          }
        }
      },
      post {
        path("order" / "cancel") {
          entity(as[OrderModel.Cancel]) {
            order =>
              {
                if (order.orderId.isEmpty && order.outOrder.isEmpty) {
                  throw new Exception("orderId or outOrder required one")
                }
                logger.info(order.logJson)
                val result = userService
                  .info(order.apiKey)
                  .map {
                    case Some(value) =>
                      MD5Util.md5(
                        value.apiSecret + order.orderId
                          .getOrElse(order.outOrder.get)
                      ) == order.sign
                    case None => throw new Exception("sign invalid")
                  }
                  .flatMap { _ =>
                    if (order.orderId.isDefined)
                      Future.successful(order.orderId.get.toLong)
                    else {
                      orderService.infoOutOrder(order.outOrder.get).map {
                        case Some(order) => order.orderId
                        case None        => throw new Exception("order not exit")
                      }
                    }
                  }
                  .flatMap { orderId =>
                    sharding
                      .entityRefFor(
                        OrderBase.typeKey,
                        OrderBase.typeKey.name
                      )
                      .ask(
                        OrderBase.Cancel(
                          orderId
                        )
                      )(3.seconds)
                      .map {
                        case OrderBase.CancelOk() => 1
                        case OrderBase.CancelFail(msg) =>
                          throw new Exception(msg)
                      }
                  }
                onComplete(result) {
                  case Failure(exception) => fail(exception.getMessage)
                  case Success(value) => {
                    if (value == 1) {
                      ok
                    } else {
                      fail("order not found")
                    }
                  }
                }
              }
          }
        }
      }
    )

}
