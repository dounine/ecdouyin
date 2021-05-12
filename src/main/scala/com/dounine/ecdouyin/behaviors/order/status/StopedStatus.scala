package com.dounine.ecdouyin.behaviors.order.status

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.ecdouyin.behaviors.mechine.MechineBase
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.tools.json.JsonParse
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.ecdouyin.behaviors.order.OrderBase._
import com.dounine.ecdouyin.behaviors.order.status.BusyStatus.logger
import com.dounine.ecdouyin.model.types.service.PayStatus
import com.dounine.ecdouyin.service.OrderService
import com.dounine.ecdouyin.tools.util.ServiceSingleton
import scala.concurrent.duration._
import scala.util.{Failure, Success}
object StopedStatus extends JsonParse {

  private final val logger: Logger =
    LoggerFactory.getLogger(StopedStatus.getClass)

  def apply(
      context: ActorContext[BaseSerializer],
      timers: TimerScheduler[BaseSerializer]
  ): (
      (
          State,
          BaseSerializer,
          (State, BaseSerializer) => Effect[BaseSerializer, State]
      ) => Effect[BaseSerializer, State],
      (
          State,
          BaseSerializer,
          (State, BaseSerializer) => State
      ) => State,
      Class[_]
  ) = {
    val sharding = ClusterSharding(context.system)
    val orderService = ServiceSingleton.get(classOf[OrderService])
    val commandHandler: (
        State,
        BaseSerializer,
        (State, BaseSerializer) => Effect[BaseSerializer, State]
    ) => Effect[BaseSerializer, State] = (
        state: State,
        command: BaseSerializer,
        _: (State, BaseSerializer) => Effect[BaseSerializer, State]
    ) =>
      command match {
        case UpdateOk(_, _) => {
          logger.info(command.logJson)
          Effect.none
        }
        case UpdateFail(_, _, _) => {
          logger.error(command.logJson)
          Effect.none
        }
        case Recovery() => {
          logger.info(command.logJson)
          Effect.none
        }
        case MechineBase.OrderPaySuccess(order) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              val updateOrder = order.copy(
                status = PayStatus.payed
              )
              context.pipeToSelf(
                orderService.update(
                  updateOrder
                )
              ) {
                case Failure(exception) =>
                  UpdateFail(
                    before = order,
                    after = updateOrder,
                    msg = exception.getMessage
                  )
                case Success(value) =>
                  UpdateOk(before = order, after = updateOrder)
              }
            })
        }
        case MechineBase.OrderPayFail(order, status) => {
          logger.error(command.logJson)
          val updateOrder = order.copy(
            payCount = order.payCount + 1,
            status = if (order.payCount >= 2) {
              PayStatus.payerr
            } else order.status
          )
          Effect
            .persist(MechineBase.OrderPayFail(updateOrder, status))
            .thenRun((latest: State) => {
              context.pipeToSelf(
                orderService.update(
                  updateOrder
                )
              ) {
                case Failure(exception) =>
                  UpdateFail(
                    before = order,
                    after = updateOrder,
                    msg = exception.getMessage
                  )
                case Success(value) =>
                  UpdateOk(before = order, after = updateOrder)
              }
            })
        }
        case Shutdown() => {
          logger.info(command.logJson)
          if (state.data.mechines.isEmpty) {
            Effect.stop()
          } else
            Effect.none.thenRun((latest: State) => {
              latest.data.mechines.foreach(id => {
                sharding
                  .entityRefFor(
                    MechineBase.typeKey,
                    id
                  )
                  .tell(
                    MechineBase.Shutdown()(context.self)
                  )
              })
            })
        }
        case Run() => {
          logger.info(command.logJson)
          Effect.none
            .thenRun((_: State) => {
              val orderService = ServiceSingleton.get(classOf[OrderService])
              context.pipeToSelf(orderService.all(PayStatus.normal)) {
                case Failure(exception) => RunFail(exception.getMessage)
                case Success(value)     => RunOk(value)
              }
            })
        }
        case RunOk(_) => {
          logger.info(command.logJson)
          Effect.persist(command).thenUnstashAll()
        }
        case RunFail(_) => {
          logger.error(command.logJson)
          Effect.none
        }
        case _ => {
          logger.info("stash -> {}", command.logJson)
          Effect.stash()
        }
      }

    val defaultEvent
        : (State, BaseSerializer, (State, BaseSerializer) => State) => State =
      (
          state: State,
          command: BaseSerializer,
          defaultEvent: (State, BaseSerializer) => State
      ) => {
        command match {
          case MechineBase.OrderPaySuccess(order) => {
            Stoped(
              state.data.copy(
                handOrders =
                  state.data.handOrders.filterNot(_._1 == order.orderId)
              )
            )
          }
          case MechineBase.OrderPayFail(order, status) => {
            Stoped(
              state.data.copy(
                waitOrders = if (order.status == PayStatus.payerr) {
                  state.data.waitOrders
                } else {
                  state.data.waitOrders ++ Map(
                    order.orderId -> order.copy(
                      payCount = order.payCount
                    )
                  )
                },
                handOrders =
                  state.data.handOrders.filterNot(_._1 == order.orderId)
              )
            )
          }

          case e @ Shutdown() => {
            Shutdowning(
              state.data.copy(
                shutdown = Option(e.replyTo)
              )
            )
          }
          case RunOk(list) => {
            Busy(
              state.data.copy(
                waitOrders = list
                  .map(item => {
                    (item.orderId, item)
                  })
                  .toMap
              )
            )
          }
          case e @ _ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Stoped])
  }
}
