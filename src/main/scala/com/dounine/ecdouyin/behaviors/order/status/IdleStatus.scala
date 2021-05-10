package com.dounine.ecdouyin.behaviors.order.status

import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.ecdouyin.behaviors.mechine.MechineBase
import com.dounine.ecdouyin.behaviors.order.OrderBase._
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.model.types.service.{MechinePayStatus, PayStatus}
import com.dounine.ecdouyin.service.{OrderService, UserService}
import com.dounine.ecdouyin.tools.json.JsonParse
import com.dounine.ecdouyin.tools.util.ServiceSingleton
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object IdleStatus extends JsonParse {

  private final val logger: Logger =
    LoggerFactory.getLogger(IdleStatus.getClass)

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
    val userService = ServiceSingleton.get(classOf[UserService])
    val commandHandler: (
        State,
        BaseSerializer,
        (State, BaseSerializer) => Effect[BaseSerializer, State]
    ) => Effect[BaseSerializer, State] = (
        state: State,
        command: BaseSerializer,
        defaultCommand: (State, BaseSerializer) => Effect[BaseSerializer, State]
    ) =>
      command match {
        case e @ WebPaySuccess(order) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              val updateOrder = order.copy(
                status = PayStatus.payed
              )
              context.pipeToSelf(
                orderService
                  .update(
                    updateOrder
                  )
                  .flatMap(_ => {
                    userService.releaseMargin(
                      order.apiKey,
                      order.margin
                    )
                  })(context.executionContext)
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
        case WebPayFail(order, msg) => {
          logger.error(command.logJson)
          val updateOrder = order.copy(
            payCount = order.payCount + 1,
            margin = if (order.payCount >= 2) {
              BigDecimal("0.00")
            } else order.margin,
            status = if (order.payCount >= 2) {
              PayStatus.payerr
            } else order.status
          )
          Effect
            .persist(WebPayFail(updateOrder, msg))
            .thenRun((latestState: State) => {
              context.pipeToSelf(
                orderService
                  .update(
                    updateOrder
                  )
                  .flatMap(_ => {
                    if (updateOrder.status == PayStatus.payerr) {
                      userService.unMargin(
                        order.apiKey,
                        order.margin
                      )
                    } else Future.successful(1)
                  })(context.executionContext)
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
        case e @ Create(order) => {
          logger.info(command.logJson)
          Effect.none.thenRun((latest: State) => {
            context.pipeToSelf(
              orderService
                .add(order)
                .flatMap(_ =>
                  userService.margin(
                    order.apiKey,
                    order.margin
                  )
                )(context.executionContext)
            ) {
              case Failure(exception) => CreateSelfFail(e, exception.getMessage)
              case Success(value)     => CreateSelfOk(e, value)
            }
          })
        }
        case CreateSelfOk(request, orderId) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              if (
                latest.data.mechines.nonEmpty && !timers
                  .isTimerActive(intervalName)
              ) {
                timers.startTimerAtFixedRate(
                  intervalName,
                  Interval(),
                  3.seconds
                )
              }
              request.replyTo.tell(
                CreateOk(orderId)
              )
            })
        }
        case CreateSelfFail(request, msg) => {
          logger.error(command.logJson)
          Effect.none.thenRun((latest: State) => {
            request.replyTo.tell(CreateFail(msg))
          })
        }
        case UpdateOk(_, _) => {
          logger.info(command.logJson)
          Effect.none
        }
        case UpdateFail(_, _, _) => {
          logger.error(command.logJson)
          Effect.none
        }
        case MechineBase.OrderPaySuccess(order) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              val updateOrder = order.copy(
                mechineStatus = MechinePayStatus.payed
              )
              context.pipeToSelf(
                orderService
                  .updateMechineStatus(
                    order.orderId,
                    MechinePayStatus.payerr
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
            mechineStatus = MechinePayStatus.payerr
          )
          Effect
            .persist(MechineBase.OrderPayFail(updateOrder, status))
            .thenRun((latestState: State) => {
              context.pipeToSelf(
                orderService.updateMechineStatus(
                  order.orderId,
                  MechinePayStatus.payerr
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
            Effect
              .persist(command)
              .thenRun((latestState: State) => {
                latestState.data.mechines.foreach(id => {
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
        }
        case Interval() => {
          logger.info(command.logJson)
          Effect.none.thenRun((latest: State) => {
            if (latest.data.waitOrders.nonEmpty) {
              val order = latest.data.waitOrders.values.toList
                .minBy(_.createTime)
              context.self.tell(
                OrderHand(
                  order = order,
                  mechineId = latest.data.mechines.head
                )
              )
            }
          })
        }
        case e @ MechineBase.Enable(_) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              if (!timers.isTimerActive(intervalName)) {
                timers.startTimerAtFixedRate(
                  intervalName,
                  Interval(),
                  3.seconds
                )
              }
            })
        }
        case MechineBase.Disable(_) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              if (latest.getClass == classOf[Busy]) {
                timers.cancel(intervalName)
              } else {
                timers.startTimerAtFixedRate(
                  intervalName,
                  Interval(),
                  3.seconds
                )
              }
            })
        }
        case OrderHand(order, mechineId) => {
          logger.info(command.logJson)
          Effect.none
            .thenRun((latestState: State) => {
              sharding
                .entityRefFor(
                  MechineBase.typeKey,
                  mechineId
                )
                .tell(
                  MechineBase.CreateOrder(
                    order = order
                  )(context.self)
                )
            })
        }
        case MechineBase.CreateOrderOk(request) => {
          logger.info(command.logJson)
          Effect.persist(command)
        }
        case MechineBase.CreateOrderFail(_, _) => {
          logger.error(command.logJson)
          Effect.persist(command)
        }
        case e @ Cancel(orderId) => {
          logger.info(command.logJson)
          Effect.none.thenRun((latest: State) => {
            if (
              latest.data.handOrders.exists(
                _._1 == orderId
              )
            ) {
              e.replyTo.tell(CancelFail("order handing"))
            } else {
              context.pipeToSelf(
                orderService.cancelOrder(orderId)
              ) {
                case Failure(exception) =>
                  CancelSelfFail(e, exception.getMessage)
                case Success(value) => {
                  if (value == 1) {
                    CancelSelfOk(e)
                  } else {
                    CancelSelfFail(e, "order not exit")
                  }
                }
              }
            }
          })
        }
        case CancelSelfOk(request) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              request.replyTo.tell(CancelOk())
            })
        }
        case CancelSelfFail(request, msg) => {
          logger.error(command.logJson)
          Effect.none.thenRun((latest: State) => {
            request.replyTo.tell(CancelFail(msg))
          })
        }
        case _ => defaultCommand(state, command)
      }

    val defaultEvent
        : (State, BaseSerializer, (State, BaseSerializer) => State) => State =
      (
          state: State,
          command: BaseSerializer,
          defaultEvent: (State, BaseSerializer) => State
      ) => {
        command match {
          case CreateSelfOk(request, orderId) => {
            Idle(
              state.data.copy(
                waitOrders = state.data.waitOrders ++ Map(
                  orderId -> request.order.copy(
                    orderId = orderId
                  )
                )
              )
            )
          }
          case WebPaySuccess(order) => {
            Idle(
              state.data.copy(
                handOrders =
                  state.data.handOrders.filterNot(_._1 == order.orderId)
              )
            )
          }
          case WebPayFail(order, msg) => {
            Idle(
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
          case MechineBase.OrderPaySuccess(order) => {
            Idle(
              state.data.copy(
                handOrders = state.data.handOrders.map(item => {
                  if (item._1 == order.orderId) {
                    item.copy(
                      _2 = item._2.copy(
                        mechineStatus = MechinePayStatus.payed
                      )
                    )
                  } else item
                })
              )
            )
          }
          case MechineBase.OrderPayFail(order, status) => {
            Idle(
              state.data.copy(
                handOrders = state.data.handOrders.map(item => {
                  if (item._1 == order.orderId) {
                    item.copy(
                      _2 = item._2.copy(
                        mechineStatus = MechinePayStatus.payerr
                      )
                    )
                  } else item
                })
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
          case MechineBase.Enable(id) => {
            Idle(
              state.data.copy(
                mechines = state.data.mechines ++ Set(id)
              )
            )
          }
          case MechineBase.Disable(id) => {
            val mechines = state.data.mechines.filterNot(_ == id)
            if (mechines.isEmpty) {
              Busy(
                state.data.copy(
                  mechines = mechines
                )
              )
            } else {
              Idle(
                state.data.copy(
                  mechines = mechines
                )
              )
            }
          }
          case MechineBase.CreateOrderOk(request) => {
            Idle(
              state.data.copy(
                waitOrders = state.data.waitOrders
                  .filterNot(_._1 == request.order.orderId),
                handOrders =
                  state.data.handOrders ++ Map(
                    request.order.orderId -> request.order
                  )
              )
            )
          }
          case MechineBase.CreateOrderFail(request, msg) => {
            Idle(
              state.data.copy(
                waitOrders = state.data.waitOrders ++ Map(
                  request.order.orderId -> request.order
                ),
                handOrders =
                  state.data.handOrders.filterNot(_._1 == request.order.orderId)
              )
            )
          }
          case CancelSelfOk(request) => {
            Idle(
              state.data.copy(
                waitOrders =
                  state.data.waitOrders.filterNot(_._1 == request.orderId)
              )
            )
          }
          case e @ _ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Idle])
  }
}
