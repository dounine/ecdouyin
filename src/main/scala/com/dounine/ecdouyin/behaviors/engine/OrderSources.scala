package com.dounine.ecdouyin.behaviors.engine

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.event.LogMarker
import akka.stream.Attributes
import akka.stream.scaladsl.Flow
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel}
import com.dounine.ecdouyin.model.types.service.PayStatus
import com.dounine.ecdouyin.service.OrderService
import com.dounine.ecdouyin.tools.json.JsonParse
import com.dounine.ecdouyin.tools.util.ServiceSingleton
import org.slf4j.LoggerFactory

import scala.concurrent.Future

object OrderSources extends JsonParse {

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
              orders = orders.filterNot(_.orderId == order.orderId)
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
      .log()
      .mapAsync(1) {
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

  }

}
