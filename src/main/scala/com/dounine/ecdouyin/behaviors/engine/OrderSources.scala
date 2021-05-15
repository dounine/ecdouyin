package com.dounine.ecdouyin.behaviors.engine

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.event.LogMarker
import akka.stream.Attributes
import akka.stream.scaladsl.Flow
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel}
import com.dounine.ecdouyin.model.types.service.PayStatus
import com.dounine.ecdouyin.service.{OrderService, UserService}
import com.dounine.ecdouyin.tools.json.{ActorSerializerSuport, JsonParse}
import com.dounine.ecdouyin.tools.util.{DingDing, ServiceSingleton}
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.Future

object OrderSources extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(OrderSources.getClass)

  sealed trait Event extends BaseSerializer

  case class Create(order: OrderModel.DbInfo) extends Event

  case class Cancel(orderId: Long) extends Event

  case class PayError(request: QrcodeSources.CreateOrderPush, error: String)
      extends Event

  case class QueryOrderInit() extends Event

  private case class QueryOrder(repeat: Int, pub: Boolean) extends Event

  private case class QueryOrderOk(
      request: QueryOrder,
      orders: Seq[OrderModel.DbInfo]
  ) extends Event

  private case class QueryOrderFail(request: QueryOrder, error: String)
      extends Event

  private case class UpdateOrderToUser(
      order: OrderModel.DbInfo,
      margin: BigDecimal,
      paySuccess: Boolean,
      repeat: Int
  ) extends Event

  private case class UpdateOrderToUserOk(request: UpdateOrderToUser)
      extends Event

  private case class UpdateOrderToUserFail(
      request: UpdateOrderToUser,
      error: String
  ) extends Event

  case class PaySuccess(request: QrcodeSources.CreateOrderPush) extends Event

  case class AppWorkPush(
      appInfo: AppSources.AppInfo
  ) extends Event

  case class OrderCallback(order: OrderModel.DbInfo) extends Event

  case class OrderCallbackOk(request: OrderCallback, response: Option[String])
      extends Event

  case class OrderCallbackFail(request: OrderCallback, error: String)
      extends Event

  implicit class FlowLog(data: Flow[BaseSerializer, BaseSerializer, NotUsed])
      extends JsonParse {
    def log(): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
      data
        .logWithMarker(
          s"orderMarker",
          (e: BaseSerializer) =>
            LogMarker(
              name = s"orderMarker"
            ),
          (e: BaseSerializer) => e.logJson
        )
        .withAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Info
          )
        )
    }
  }

  def createCoreFlow(
      system: ActorSystem[_]
  ): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
    implicit val ec = system.executionContext
    val orderService = ServiceSingleton.get(classOf[OrderService])
    val userService = ServiceSingleton.get(classOf[UserService])
    Flow[BaseSerializer]
      .collectType[Event]
      .log()
      .statefulMapConcat { () =>
        var orders = Set[OrderModel.DbInfo]()
        var query = false

        {
          case QueryOrderInit() => {
            QueryOrder(repeat = 0, pub = true) :: Nil
          }
          case QueryOrder(repeat, pub) => {
            if (!query) {
              query = true
              QueryOrder(repeat = 0, pub = false) :: Nil
            } else {
              if (repeat > 0 && repeat < 3 && !pub) {
                QueryOrder(repeat = repeat + 1, pub = false) :: Nil
              } else Nil
            }
          }
          case QueryOrderOk(request, _orders) => {
            orders = _orders.toSet
            Nil
          }
          case QueryOrderFail(request, error) => {
            if (request.repeat < 3) {
              request.copy(repeat = request.repeat + 1) :: Nil
            } else Nil
          }
          case UpdateOrderToUserOk(request) => {
            Nil
          }
          case OrderCallbackOk(_, _) => {
            Nil
          }
          case OrderCallbackFail(_, _) => {
            Nil
          }
          case UpdateOrderToUserFail(request, error) => {
            if (request.repeat < 3) {
              request.copy(repeat = request.repeat + 1) :: Nil
            } else Nil
          }
          case request @ AppWorkPush(appInfo) => {
            if (orders.nonEmpty) {
              val earlierOrder = orders.minBy(_.createTime)
              orders = orders.filterNot(_.orderId == earlierOrder.orderId)
              QrcodeSources.CreateOrderPush(request, earlierOrder) :: Nil
            } else {
              AppSources.Idle(
                appInfo = appInfo
              ) :: Nil
            }
          }
          case Create(order) => {
            orders = orders ++ Set(order)
            DingDing.sendMessage(
              DingDing.MessageType.order,
              data = DingDing.MessageData(
                markdown = DingDing.Markdown(
                  title = "定单通知",
                  text = s"""
                            |## 创建成功
                            | - apiKey: ${order.apiKey}
                            | - money: ${order.money}
                            | - orderId: ${order.orderId}
                            | - outOrder: ${order.outOrder}
                            | - account: ${order.account}
                            | - payCount: ${order.payCount}
                            | - platform: ${order.platform}
                            | - createTime: ${order.createTime}
                            | - notifyTime -> ${LocalDateTime.now()}
                            |""".stripMargin
                )
              ),
              system
            )
            Nil
          }
          case PaySuccess(request) => {
            val order = request.order.copy(
              status = PayStatus.payed,
              margin = BigDecimal("0")
            )
            DingDing.sendMessage(
              DingDing.MessageType.payed,
              data = DingDing.MessageData(
                markdown = DingDing.Markdown(
                  title = "充值通知",
                  text = s"""
                            |## 充值成功
                            | - apiKey: ${order.apiKey}
                            | - money: ${order.money}
                            | - orderId: ${order.orderId}
                            | - outOrder: ${order.outOrder}
                            | - account: ${order.account}
                            | - payCount: ${order.payCount}
                            | - platform: ${order.platform}
                            | - createTime: ${order.createTime}
                            | - notifyTime -> ${LocalDateTime.now()}
                            |""".stripMargin
                )
              ),
              system
            )
            UpdateOrderToUser(
              request.order.copy(
                status = PayStatus.payed,
                margin = BigDecimal("0")
              ),
              margin = request.order.margin,
              paySuccess = true,
              repeat = 0
            ) :: OrderCallback(order) :: Nil
          }
          case PayError(request, error) => {
            var order = request.order.copy(
              payCount = request.order.payCount + 1
            )
            order = if (order.payCount >= 3) {
              order.copy(
                status = PayStatus.payerr
              )
            } else {
              order.copy(
                payCount = request.order.payCount + 1
              )
            }
            if (order.status == PayStatus.payerr) {
              orders = orders.filterNot(_.orderId == order.orderId)
              val orderTmp = order.copy(
                margin = BigDecimal("0")
              )
              DingDing.sendMessage(
                DingDing.MessageType.payerr,
                data = DingDing.MessageData(
                  markdown = DingDing.Markdown(
                    title = "充值通知",
                    text = s"""
                              |## 充值失败、入库
                              | - apiKey: ${order.apiKey}
                              | - money: ${order.money}
                              | - orderId: ${order.orderId}
                              | - outOrder: ${order.outOrder}
                              | - account: ${order.account}
                              | - payCount: ${order.payCount}
                              | - platform: ${order.platform}
                              | - createTime: ${order.createTime}
                              | - errorMsg: ${error}
                              | - notifyTime -> ${LocalDateTime.now()}
                              |""".stripMargin
                  )
                ),
                system
              )
              UpdateOrderToUser(
                orderTmp,
                margin = order.margin,
                paySuccess = false,
                repeat = 0
              ) :: OrderCallback(orderTmp) :: Nil
            } else {
              DingDing.sendMessage(
                DingDing.MessageType.payerr,
                data = DingDing.MessageData(
                  markdown = DingDing.Markdown(
                    title = "充值通知",
                    text = s"""
                              |## 充值失败、重试中
                              | - apiKey: ${order.apiKey}
                              | - money: ${order.money}
                              | - orderId: ${order.orderId}
                              | - outOrder: ${order.outOrder}
                              | - account: ${order.account}
                              | - payCount: ${order.payCount}
                              | - platform: ${order.platform}
                              | - createTime: ${order.createTime}
                              | - errorMsg: ${error}
                              | - notifyTime -> ${LocalDateTime.now()}
                              |""".stripMargin
                  )
                ),
                system
              )
              orders = orders ++ Set(
                order
              )
              UpdateOrderToUser(
                order,
                margin = BigDecimal("0"),
                paySuccess = false,
                repeat = 0
              ) :: Nil
            }
          }
          case Cancel(orderId) => {
            val event = orders.find(_.orderId == orderId) match {
              case Some(order) =>
                UpdateOrderToUser(
                  order.copy(
                    status = PayStatus.cancel,
                    margin = BigDecimal("0")
                  ),
                  margin = order.margin,
                  paySuccess = false,
                  repeat = 0
                ) :: Nil
              case None => Nil
            }
            orders = orders.filterNot(_.orderId == orderId)
            event
          }
          case ee @ _ => ee :: Nil
        }
      }
      .log()
      .mapAsync(1) {
        case request @ OrderCallback(order) => {
          userService
            .info(order.apiKey)
            .flatMap {
              case Some(userInfo) => {
                userInfo.callback match {
                  case Some(url) =>
                    orderService
                      .callback(
                        order,
                        order.status,
                        url,
                        userInfo.apiSecret,
                        None
                      )
                      .map(result => OrderCallbackOk(request, Option(result)))
                      .recover {
                        case ee =>
                          OrderCallbackFail(request, ee.getMessage)
                      }
                  case None =>
                    Future.successful(
                      OrderCallbackFail(request, "url not config")
                    )
                }
              }
              case None =>
                Future.successful(OrderCallbackFail(request, "apiKey not exit"))
            }
            .recover {
              case ee => OrderCallbackFail(request, ee.getMessage)
            }
        }
        case request @ QueryOrder(repeat, pub) => {
          orderService
            .all(PayStatus.normal)
            .map(orders => {
              QueryOrderOk(request = request, orders = orders)
            })
            .recover {
              case e => QueryOrderFail(request, e.getMessage)
            }
        }
        case request @ UpdateOrderToUser(order, margin, paySuccess, repeat) => {
          (if (paySuccess) {
             orderService.releaseMarginOrderToUser(
               order,
               margin
             )
           } else {
             if (margin == BigDecimal("0")) {
               orderService.updateAll(order)
             } else {
               orderService.unMarginOrderAppendToUser(order, margin)
             }
           })
            .map {
              case 1 => UpdateOrderToUserOk(request)
              case _ => UpdateOrderToUserFail(request, "update fail")
            }
            .recover {
              case ee => UpdateOrderToUserFail(request, ee.getMessage)
            }
        }
        case ee @ _ => {
          Future.successful(ee)
        }
      }
      .log()

  }

}
