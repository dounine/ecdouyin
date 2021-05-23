package com.dounine.ecdouyin.router.routers

import akka.{NotUsed, actor}
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.common.{
  EntityStreamingSupport,
  JsonEntityStreamingSupport
}
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.{Directive1, Route, ValidationRejection}
import akka.stream._
import akka.stream.scaladsl.{
  Concat,
  Flow,
  GraphDSL,
  Keep,
  Merge,
  Partition,
  Sink,
  Source
}
import akka.util.ByteString
import com.dounine.ecdouyin.behaviors.cache.ReplicatedCacheBehavior
import com.dounine.ecdouyin.behaviors.engine.{CoreEngine, OrderSources}
import com.dounine.ecdouyin.behaviors.qrcode.QrcodeSources
import com.dounine.ecdouyin.model.models.RouterModel.JsonData
import com.dounine.ecdouyin.model.models.{
  BaseSerializer,
  OrderModel,
  RouterModel,
  UserModel
}
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.model.types.service.{
  MechinePayStatus,
  PayPlatform,
  PayStatus
}
import com.dounine.ecdouyin.router.routers.errors.DataException
import com.dounine.ecdouyin.service.{
  OrderService,
  OrderStream,
  UserService,
  UserStream
}
import com.dounine.ecdouyin.tools.util.{MD5Util, ServiceSingleton}
import org.slf4j.{Logger, LoggerFactory}

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
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

  val moneyInValid = "充值金额只能是[6 ~ 5000]之间的整数"
  val signInvalidMsg = "sign验证失败"
  val apiKeyNotFound = "钥匙不存在"
  val orderNotFound = "定单不存在"
  val balanceNotEnough = "帐户可用余额不足"
  val orderAndOutOrderRequireOn = "[orderId/outOrder]不能全为空"
  val cacheBehavior = system.systemActorOf(ReplicatedCacheBehavior(), "cache")
  val noSign = system.settings.config.getBoolean("app.noSign")

  def userInfo()(implicit
      system: ActorSystem[_]
  ): Directive1[UserModel.DbInfo] = {
    parameters("apiKey".as[String].optional).flatMap {
      case Some(apiKey) =>
        onComplete(UserStream.source(apiKey, system).runWith(Sink.head))
          .flatMap {
            case Failure(exception) =>
              reject(ValidationRejection("apiKey 不正确", Option(exception)))
            case Success(value) => provide(value)
          }
      case None => reject(ValidationRejection("apiKey 参数缺失"))
    }
  }

  val route: Route =
    concat(
      get {
        path("user" / "info") {
          userInfo()(system) {
            userInfo =>
              {
                parameters(
                  "platform".as[String],
                  "userId".as[String],
                  "sign".as[String]
                ) {
                  (platform, userId, sign) =>
                    {

                      val pf = PayPlatform.withName(platform)
                      import akka.actor.typed.scaladsl.AskPattern._

                      require(
                        noSign || MD5Util.md5(
                          userInfo.apiSecret + userId + platform
                        ) == sign,
                        signInvalidMsg
                      )
                      val key = s"userInfo-${pf}-${userId}"

                      val result =
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
                                      value
                                        .asInstanceOf[Option[
                                          OrderModel.UserInfo
                                        ]]
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
                          .map {
                            case Some(value) => okData(value)
                            case None        => failMsg("user not found")
                          }
                          .recover {
                            case ee => failMsg(ee.getMessage)
                          }
                      complete(result)
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
                          noSign || MD5Util.md5(
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
              userInfo()(system) {
                {
                  userInfo: UserModel.DbInfo =>
                    {
                      logger.info(querys.logJson)
                      val queryInfo = querys.toJson.jsonTo[OrderModel.Query]
                      require(
                        queryInfo.orderId.isDefined || queryInfo.outOrder.isDefined,
                        orderAndOutOrderRequireOn
                      )
                      require(
                        noSign || MD5Util.md5(
                          userInfo.apiSecret + queryInfo.orderId
                            .getOrElse(queryInfo.outOrder.get)
                        ) == queryInfo.sign,
                        signInvalidMsg
                      )
                      val result = (if (queryInfo.orderId.isDefined) {
                                      orderService.infoOrder(
                                        queryInfo.orderId.get.toLong
                                      )
                                    } else {
                                      orderService.infoOutOrder(
                                        queryInfo.outOrder.get
                                      )
                                    })
                        .map {
                          case Some(order) =>
                            okData(
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
                          case None => failMsg(orderNotFound)
                        }
                        .recover {
                          case ee => failMsg(ee.getMessage)
                        }
                      complete(result)
                    }
                }
              }
          }
        }
      },
      post {
        path("order" / "recharge2") {
          withoutSizeLimit {
            extractDataBytes {
              dataBytes: Source[ByteString, Any] =>
                {
                  userInfo()(system) {
                    userInfo =>
                      {
                        val source: Source[
                          Either[JsonData, OrderModel.Recharge],
                          NotUsed
                        ] = Source
                          .future(
                            dataBytes
                              .runFold(ByteString.empty)(_ ++ _)
                              .map((_: ByteString).utf8String)
                              .map((_: String).jsonTo[OrderModel.Recharge])
                              .map(Right.apply)
                              .recover { e: Throwable =>
                                Left(
                                  failMsg(e.getMessage)
                                )
                              }
                          )

                        val validatingFlow: Flow[
                          OrderModel.Recharge,
                          Either[JsonData, OrderModel.Recharge],
                          NotUsed
                        ] =
                          Flow[OrderModel.Recharge]
                            .map(recharge => {
                              if (
                                MD5Util.md5(
                                  userInfo.apiSecret + recharge.account + recharge.money + recharge.platform + recharge.outOrder
                                ) != recharge.sign
                              ) {
                                Left(failMsg(signInvalidMsg))
                              } else if (
                                !(recharge.money.toInt >= 6 && recharge.money.toInt <= 5000)
                              ) {
                                Left(
                                  failMsg(
                                    moneyInValid
                                  )
                                )
                              } else if (
                                userInfo.balance - BigDecimal(
                                  recharge.money
                                ) < 0
                              ) {
                                Left(
                                  failDataMsg(
                                    msg = balanceNotEnough,
                                    data = Map(
                                      "balance" -> userInfo.balance,
                                      "margin" -> userInfo.margin
                                    )
                                  )
                                )
                              } else {
                                Right(recharge)
                              }
                            })

                        val persistenceFlow: Flow[
                          OrderModel.Recharge,
                          Either[JsonData, OrderModel.DbInfo],
                          NotUsed
                        ] = Flow[OrderModel.Recharge]
                          .map(recharge => {
                            OrderModel.DbInfo(
                              orderId = 0,
                              outOrder = recharge.outOrder,
                              apiKey = recharge.apiKey,
                              account = recharge.account,
                              money = recharge.money.toInt,
                              volumn = recharge.money.toInt * 10,
                              margin = BigDecimal(recharge.money),
                              platform = recharge.platform,
                              status = PayStatus.normal,
                              payCount = 0,
                              createTime = LocalDateTime.now()
                            )
                          })
                          .via(
                            OrderStream
                              .createOrderMarginUser(system)
                              .map {
                                case Left(value) => {
                                  Left(failMsg(value.getMessage))
                                }
                                case Right(value) => Right(value._1)
                              }
                          )

                        val emitOrderToEngineFlow =
                          OrderStream
                            .createOrderToEngine(system)
                            .map(_.left.map(e => failMsg(e.getMessage)))
                            .map(_.map(s => {
                              OrderModel.CreateOrderSuccess(
                                orderId = s._1.orderId.toString,
                                outOrder = s._1.outOrder,
                                balance = s._2.balance.toString(),
                                margin = s._2.margin.toString()
                              )
                            }))

                        val sourceGraph = Source.fromGraph(GraphDSL.create() {
                          implicit builder =>
                            {
                              import GraphDSL.Implicits._

                              val partitionToSource = builder.add(
                                Partition[
                                  Either[JsonData, OrderModel.Recharge]
                                ](
                                  2,
                                  {
                                    case Left(value)  => 1
                                    case Right(value) => 0
                                  }
                                )
                              )
                              val partitionToValidating = builder.add(
                                Partition[
                                  Either[JsonData, OrderModel.Recharge]
                                ](
                                  2,
                                  {
                                    case Left(value)  => 1
                                    case Right(value) => 0
                                  }
                                )
                              )

                              val partitionToPersistence = builder.add(
                                Partition[
                                  Either[JsonData, OrderModel.DbInfo]
                                ](
                                  2,
                                  {
                                    case Left(value)  => 1
                                    case Right(value) => 0
                                  }
                                )
                              )

                              val filter1, filter2, filter3,
                                  filter4 = builder.add(
                                Flow[Either[JsonData, BaseSerializer]].collect {
                                  case Left(value) => value
                                }
                              )

                              val merge = builder.add(Merge[JsonData](4))

                              val validBuilder = builder.add(
                                Flow[Either[JsonData, OrderModel.Recharge]]
                                  .collect { case Right(value) => value }
                                  .via(validatingFlow)
                              )
                              val persistenceBuilder = builder.add(
                                Flow[Either[JsonData, OrderModel.Recharge]]
                                  .collect { case Right(value) => value }
                                  .via(persistenceFlow)
                              )

                              val emitOrderToEngine = builder.add(
                                Flow[Either[JsonData, OrderModel.DbInfo]]
                                  .collect { case Right(value) => value }
                                  .via(emitOrderToEngineFlow)
                              )

                              //┌─────────┐     ┌─────────┐    ┌────────────┐   ┌─────────┐    ┌───────┐
                              //│ source  ○────▷│  valid  ○───▷│persistence ○──▷│ engine  ○────□ client│
                              //└────○────┘     └────○────┘    └─────○──────┘   └─────────┘    └────□──┘
                              //     │               │               │                              │
                              //     │               │               ▽                              │
                              //     │               │          ┌─────────┐                         │
                              //     └───────────────┴─────────▷│  merge  │                         │
                              //                                └────○────┘                         │
                              //                                     │                              │
                              //                                     └──────────JsonData────────────┘
                              //

                              source ~> partitionToSource ~> validBuilder ~> partitionToValidating ~> persistenceBuilder ~> partitionToPersistence ~> emitOrderToEngine ~> filter1 ~> merge

                              partitionToSource.out(
                                1
                              ) ~> filter2 ~> merge.in(1)
                              partitionToValidating.out(
                                1
                              ) ~> filter3 ~> merge.in(2)
                              partitionToPersistence.out(
                                1
                              ) ~> filter4 ~> merge.in(3)

                              SourceShape(merge.out)
                            }
                        })

                        complete(sourceGraph)
                      }
                  }
                }
            }
          }
        } ~
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
                          noSign || MD5Util.md5(
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
