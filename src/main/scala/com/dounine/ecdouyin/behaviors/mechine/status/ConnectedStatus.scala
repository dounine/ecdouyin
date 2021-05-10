package com.dounine.ecdouyin.behaviors.mechine.status

import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.ecdouyin.behaviors.client.SocketBehavior
import com.dounine.ecdouyin.behaviors.mechine.MechineBase._
import com.dounine.ecdouyin.behaviors.mechine.MechineBehavior.logger
import com.dounine.ecdouyin.behaviors.mechine.status.WechatPageStatus.logger
import com.dounine.ecdouyin.behaviors.order.OrderBase
import com.dounine.ecdouyin.behaviors.qrcode.QrcodeBehavior
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.model.types.service.MechineStatus
import com.dounine.ecdouyin.model.types.service.MechineStatus.MechineStatus
import com.dounine.ecdouyin.tools.json.JsonParse
import org.slf4j.{Logger, LoggerFactory}

object ConnectedStatus extends JsonParse {

  private final val logger: Logger =
    LoggerFactory.getLogger(ConnectedStatus.getClass)

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
      Class[_],
      MechineStatus
  ) = {
    val sharding = ClusterSharding(context.system)
    val domain = context.system.settings.config.getString("app.file.domain")
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
        case QrcodeQuery(order) => {
          logger.info(command.logJson)
          Effect.none.thenRun((latest: State) => {
            sharding
              .entityRefFor(
                QrcodeBehavior.typeKey,
                order.orderId.toString
              )
              .tell(
                QrcodeBehavior.Create(order)(context.self)
              )
          })
        }
        case QrcodeBehavior.CreateOk(request, qrcode) => {
          logger.info(command.logJson)
          val order = request.order
          Effect.none.thenRun((latest: State) => {
            timers.startSingleTimer(
              timeoutName,
              SocketTimeout(Option.empty),
              state.data.orderTimeout
            )
            sharding
              .entityRefFor(
                OrderBase.typeKey,
                OrderBase.typeKey.name
              )
              .tell(
                CreateOrderOk(CreateOrder(request.order)(null))
              )
            latest.data.actor.foreach(
              _.tell(
                SocketBehavior.OrderCreate(
                  image = qrcode,
                  domain = domain,
                  orderId = order.orderId,
                  money = order.money,
                  volume = order.volumn,
                  platform = order.platform,
                  timeout = state.data.orderTimeout
                )
              )
            )
          })
        }
        case QrcodeBehavior.CreateFail(request, msg) => {
          logger.error(command.logJson)
          Effect.none.thenRun((latest: State) => {
            sharding
              .entityRefFor(
                OrderBase.typeKey,
                OrderBase.typeKey.name
              )
              .tell(
                CreateOrderFail(CreateOrder(request.order)(null), msg)
              )
          })
        }
        case e @ CreateOrder(order) => {
          logger.info(command.logJson)
          state.data.order match {
            case Some(order) =>
              Effect.none.thenRun((latest: State) => {
                e.replyTo.tell(CreateOrderFail(e, "order exit"))
              })
            case None => {
              Effect
                .persist(command)
                .thenRun((latestState: State) => {
                  context.self.tell(
                    QrcodeQuery(order)
                  )
                  e.replyTo.tell(
                    Disable(latestState.data.mechineId)
                  )
                })
            }
          }
        }
        case SocketTimeout(screen) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latestState: State) => {
              sharding
                .entityRefFor(
                  OrderBase.typeKey,
                  OrderBase.typeKey.name
                )
                .tell(
                  OrderPayFail(
                    order = latestState.data.order.get,
                    status = MechineStatus.connected
                  )
                )
              sharding
                .entityRefFor(
                  OrderBase.typeKey,
                  OrderBase.typeKey.name
                )
                .tell(
                  Enable(
                    latestState.data.mechineId
                  )
                )
            })
        }
        case e @ Shutdown() => {
          logger.info(command.logJson)
          Effect
            .stop()
            .thenRun((latest: State) => {
              e.replyTo.tell(ShutdownOk(e, latest.data.mechineId))
              latest.data.order.foreach(order => {
                sharding
                  .entityRefFor(
                    OrderBase.typeKey,
                    OrderBase.typeKey.name
                  )
                  .tell(
                    OrderPayFail(
                      order = order,
                      status = MechineStatus.connected
                    )
                  )
              })
              sharding
                .entityRefFor(
                  OrderBase.typeKey,
                  OrderBase.typeKey.name
                )
                .tell(
                  Disable(latest.data.mechineId)
                )
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
          case SocketDisconnect() => {
            Disconnected(
              state.data.copy(
                actor = Option.empty
              )
            )
          }
          case e @ _ => defaultEvent(state, e)
        }
      }

    (commandHandler, defaultEvent, classOf[Connected], MechineStatus.connected)
  }
}
