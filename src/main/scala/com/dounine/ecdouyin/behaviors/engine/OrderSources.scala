package com.dounine.ecdouyin.behaviors.engine

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.Attributes
import akka.stream.scaladsl.Flow
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel, UserModel}
import com.dounine.ecdouyin.model.types.service.PayStatus
import com.dounine.ecdouyin.service.{OrderService, UserService}
import com.dounine.ecdouyin.tools.util.ServiceSingleton
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.Future

object OrderSources {

  private val logger = LoggerFactory.getLogger(OrderSources.getClass)
  case class Create(order: OrderModel.DbInfo) extends BaseSerializer

  case class Cancel(orderId: Long) extends BaseSerializer

  case class CreateOrderPush(
      request: AppSources.AppWorkPush,
      order: OrderModel.DbInfo
  ) extends BaseSerializer

  case class PayError(request: CreateOrderPush, error: String)
      extends BaseSerializer

  case class QueryOrder(repeat: Int) extends BaseSerializer

  case class QueryOrderOk(request: QueryOrder, orders: Seq[OrderModel.DbInfo])
      extends BaseSerializer

  case class QueryOrderFail(request: QueryOrder, error: String)
      extends BaseSerializer

  case class UpdateOrderToUser(
      order: OrderModel.DbInfo,
      margin: BigDecimal,
      paySuccess: Boolean,
      repeat: Int
  ) extends BaseSerializer

  case class UpdateOrderToUserOk(request: UpdateOrderToUser)
      extends BaseSerializer

  case class UpdateOrderToUserFail(
      request: UpdateOrderToUser,
      error: String
  ) extends BaseSerializer

  case class PaySuccess(request: CreateOrderPush) extends BaseSerializer

  def createCoreFlow(
      system: ActorSystem[_]
  ): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
    implicit val ec = system.executionContext
    val orderService = ServiceSingleton.get(classOf[OrderService])
    Flow[BaseSerializer]
      .statefulMapConcat { () =>
        var orders = Set[OrderModel.DbInfo]()
        var query = false

        {
          case QueryOrder(repeat) => {
            if (!query) {
              query = true
              QueryOrder(0) :: Nil
            } else {
              if (repeat > 0 && repeat < 3) {
                QueryOrder(repeat + 1) :: Nil
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
          case UpdateOrderToUserFail(request, error) => {
            if (request.repeat < 3) {
              request.copy(repeat = request.repeat + 1) :: Nil
            } else Nil
          }
          case request @ AppSources.AppWorkPush(appInfo) => {
            if (orders.nonEmpty) {
              val earlierOrder = orders.minBy(_.createTime)
              orders = orders.filterNot(_.orderId == earlierOrder.orderId)
              CreateOrderPush(request, earlierOrder) :: Nil
            } else {
              AppSources.Idle(
                appInfo = appInfo
              ) :: Nil
            }
          }
          case Create(order) => {
            orders = orders ++ Set(order)
            Nil
          }
          case PaySuccess(request) => {
            UpdateOrderToUser(
              request.order.copy(
                status = PayStatus.payed,
                margin = BigDecimal("0")
              ),
              margin = request.order.margin,
              paySuccess = true,
              repeat = 0
            ) :: Nil
          }
          case PayError(request, error) => {
            val order = if (request.order.payCount >= 3) {
              request.order.copy(
                status = PayStatus.payerr
              )
            } else {
              request.order.copy(
                payCount = request.order.payCount + 1
              )
            }
            if (order.status == PayStatus.payerr) {
              UpdateOrderToUser(
                order.copy(
                  margin = BigDecimal("0")
                ),
                margin = order.margin,
                paySuccess = false,
                repeat = 0
              ) :: Nil
            } else {
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
      .mapAsync(1) {
        case request @ QueryOrder(repeat) => {
          orderService
            .all(PayStatus.normal)
            .map(orders => QueryOrderOk(request = request, orders = orders))
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
        case ee @ _ => Future.successful(ee)
      }

  }

}
