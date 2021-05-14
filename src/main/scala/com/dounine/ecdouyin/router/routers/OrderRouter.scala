package com.dounine.ecdouyin.router.routers

import akka.actor
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.Route
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.ecdouyin.behaviors.cache.ReplicatedCacheBehavior
import com.dounine.ecdouyin.behaviors.engine.{CoreEngine, OrderSources}
import com.dounine.ecdouyin.behaviors.qrcode.QrcodeSources
import com.dounine.ecdouyin.model.models.OrderModel
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.model.types.service.{
  MechinePayStatus,
  PayPlatform,
  PayStatus
}
import com.dounine.ecdouyin.service.{OrderService, UserService}
import com.dounine.ecdouyin.tools.util.{MD5Util, ServiceSingleton}
import org.slf4j.{Logger, LoggerFactory}

import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
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

  val signInvalidMsg = "特征码验证失败"
  val apiKeyNotFound = "钥匙不存在"
  val orderNotFound = "定单不存在"
  val balanceNotEnough = "帐户可用余额不足"
  val orderAndOutOrderRequireOn = "[orderId/outOrder]不能全为空"
  val cacheBehavior = system.systemActorOf(ReplicatedCacheBehavior(), "cache")

  val route: Route =
    concat(
      get {
        path("user" / "info") {
          parameters("platform".as[String], "userId".as[String]) {
            (platform, userId) =>
              {

                val pf = PayPlatform.withName(platform)
                import akka.actor.typed.scaladsl.AskPattern._

                val key = s"userInfo-${pf}-${userId}"
                onComplete(
                  cacheBehavior
                    .ask(
                      ReplicatedCacheBehavior.GetCache(key)
                    )(3.seconds, system.scheduler)
                    .flatMap {
                      cacheValue =>
                        {
                          cacheValue.value match {
                            case Some(value) =>
                              Future.successful(
                                value.asInstanceOf[Option[OrderModel.UserInfo]]
                              )
                            case None =>
                              orderService
                                .userInfo(pf, userId)
                                .map(result => {
                                  if (result.isDefined) {
                                    cacheBehavior.tell(
                                      ReplicatedCacheBehavior.PutCache(
                                        key = key,
                                        value = result,
                                        timeout = 1.days
                                      )
                                    )
                                  }
                                  result
                                })
                          }
                        }
                    }
                ) {
                  case Failure(exception) => {
                    exception.printStackTrace()
                    fail(exception.getMessage)
                  }
                  case Success(value) =>
                    value match {
                      case Some(info) => ok(info)
                      case None       => fail("user not found")
                    }
                }
              }

          }
        } ~
          path("order" / "qrcode") {
            val sharding = ClusterSharding(system)
            onComplete(
              orderService
                .infoOrder(1)
                .flatMap(result => {
                  QrcodeSources
                    .createQrcodeSource(
                      system,
                      result.get
                    )
                    .map {
                      case Left(value)  => throw value
                      case Right(value) => value._3
                    }
                    .runWith(Sink.head)
//                  sharding
//                    .entityRefFor(
//                      QrcodeBehavior.typeKey,
//                      "1"
//                    )
//                    .ask(
//                      QrcodeBehavior.Create(result.get)
//                    )(20.seconds)
                })
            ) {
              case Failure(exception) => fail(exception.getMessage)
              case Success(value)     => ok(value)
            }
          } ~
          path("balance") {
            parameterMap {
              querys =>
                {
                  logger.info(querys.logJson)
                  val queryInfo = querys.toJson.jsonTo[OrderModel.Balance]
                  val result = userService
                    .info(queryInfo.apiKey)
                    .map {
                      case Some(value) => {
                        require(
                          MD5Util.md5(
                            value.apiSecret
                          ) == queryInfo.sign,
                          signInvalidMsg
                        )
                        ok(
                          Map(
                            "balance" -> value.balance,
                            "margin" -> value.margin
                          )
                        )
                      }
                      case None => throw new Exception(apiKeyNotFound)
                    }
                  onComplete(result) {
                    case Failure(exception) => fail(exception.getMessage)
                    case Success(value)     => value
                  }
                }
            }
          } ~ path("order" / "info") {
          parameterMap {
            querys =>
              {
                logger.info(querys.logJson)
                val queryInfo = querys.toJson.jsonTo[OrderModel.Query]
                require(
                  queryInfo.orderId.isDefined || queryInfo.outOrder.isDefined,
                  orderAndOutOrderRequireOn
                )
                val result = userService
                  .info(queryInfo.apiKey)
                  .flatMap {
                    case Some(value) => {
                      require(
                        MD5Util.md5(
                          value.apiSecret + queryInfo.orderId
                            .getOrElse(queryInfo.outOrder.get)
                        ) == queryInfo.sign,
                        signInvalidMsg
                      )
                      if (queryInfo.orderId.isDefined) {
                        orderService.infoOrder(queryInfo.orderId.get.toLong)
                      } else {
                        orderService.infoOutOrder(queryInfo.outOrder.get)
                      }
                    }
                    case None => throw new Exception(signInvalidMsg)
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
                      case None => fail(orderNotFound)
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
                      require(
                        MD5Util.md5(
                          userInfo.apiSecret + order.account + order.money + order.platform + order.outOrder
                        ) == order.sign,
                        signInvalidMsg
                      )
                      if (
                        !order.money.matches(
                          "\\d+"
                        ) || !(order.money.toInt >= 6 && order.money.toInt <= 5000)
                      ) {
                        throw new Exception("充值金额只能是[6 ~ 5000]之间的整数")
                      } else if (
                        userInfo.balance - BigDecimal(order.money) < 0
                      ) {
                        Future.successful(
                          complete(
                            Map(
                              "code" -> "fail",
                              "msg" -> balanceNotEnough,
                              "data" -> Map(
                                "balance" -> userInfo.balance,
                                "margin" -> userInfo.margin
                              )
                            )
                          )
                        )
                      } else {
                        val newOrder = OrderModel.DbInfo(
                          orderId = 0,
                          outOrder = order.outOrder,
                          apiKey = order.apiKey,
                          account = order.account,
                          money = order.money.toInt,
                          volumn = order.money.toInt * 10,
                          margin = BigDecimal(order.money),
                          platform = order.platform,
                          status = PayStatus.normal,
                          payCount = 0,
                          createTime = LocalDateTime.now()
                        )

                        orderService
                          .addOrderAndMarginUser(
                            newOrder
                          )
                          .flatMap {
                            orderId =>
                              {
                                val order = newOrder.copy(orderId = orderId)
                                sharding
                                  .entityRefFor(
                                    CoreEngine.typeKey,
                                    CoreEngine.typeKey.name
                                  )
                                  .ask(
                                    CoreEngine.CreateOrder(
                                      order
                                    )
                                  )(3.seconds)
                                  .map {
                                    case CoreEngine.CreateOrderOk(request) =>
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
                                    case CoreEngine
                                          .CreateOrderFail(request, error) =>
                                      fail(
                                        error
                                      )
                                  }
                              }
                          }
                      }
                    case None => throw new Exception(apiKeyNotFound)
                  }
                onComplete(result) {
                  case Failure(exception) => fail(exception.getMessage)
                  case Success(value)     => value
                }
              }
          }
        }
      }
//      post {
//        path("order" / "cancel") {
//          entity(as[OrderModel.Cancel]) {
//            order =>
//              {
//                logger.info(order.logJson)
//                require(
//                  order.orderId.isDefined || order.outOrder.isDefined,
//                  orderAndOutOrderRequireOn
//                )
//                val result = userService
//                  .info(order.apiKey)
//                  .flatMap {
//                    case Some(value) =>
//                      require(
//                        MD5Util.md5(
//                          value.apiSecret + order.orderId
//                            .getOrElse(order.outOrder.get)
//                        ) == order.sign,
//                        signInvalidMsg
//                      )
//                      if (order.orderId.isDefined)
//                        Future.successful(order.orderId.get.toLong)
//                      else {
//                        orderService.infoOutOrder(order.outOrder.get).map {
//                          case Some(order) => order.orderId
//                          case None        => throw new Exception(orderNotFound)
//                        }
//                      }
//                    case None => throw new Exception(signInvalidMsg)
//                  }
//                  .flatMap { orderId =>
//                    sharding
//                      .entityRefFor(
//                        OrderBase.typeKey,
//                        OrderBase.typeKey.name
//                      )
//                      .ask(
//                        OrderBase.Cancel(
//                          orderId
//                        )
//                      )(3.seconds)
//                      .map {
//                        case OrderBase.CancelOk() => 1
//                        case OrderBase.CancelFail(msg) =>
//                          throw new Exception(msg)
//                      }
//                  }
//                onComplete(result) {
//                  case Failure(exception) => fail(exception.getMessage)
//                  case Success(value) => {
//                    if (value == 1) {
//                      ok
//                    } else {
//                      fail(orderNotFound)
//                    }
//                  }
//                }
//              }
//          }
//        }
//      }
    )

}
