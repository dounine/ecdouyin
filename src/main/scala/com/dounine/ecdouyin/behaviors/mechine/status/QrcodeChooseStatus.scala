package com.dounine.ecdouyin.behaviors.mechine.status

import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.ecdouyin.behaviors.mechine.MechineBase._
import com.dounine.ecdouyin.behaviors.mechine.status.PayFailStatus.logger
import com.dounine.ecdouyin.behaviors.mechine.status.WechatPageStatus.logger
import com.dounine.ecdouyin.behaviors.order.OrderBase
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.model.types.service.MechineStatus
import com.dounine.ecdouyin.model.types.service.MechineStatus.MechineStatus
import com.dounine.ecdouyin.tools.json.JsonParse
import org.slf4j.{Logger, LoggerFactory}

object QrcodeChooseStatus extends JsonParse {

  private final val logger: Logger =
    LoggerFactory.getLogger(QrcodeChooseStatus.getClass)

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
        case SocketTimeout(screen) => {
          logger.info(command.logJson)
          timers.cancel(timeoutName)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              sharding
                .entityRefFor(
                  OrderBase.typeKey,
                  OrderBase.typeKey.name
                )
                .tell(
                  OrderPayFail(
                    order = latest.data.order.get,
                    status = MechineStatus.qrcodeChoose
                  )
                )
              sharding
                .entityRefFor(
                  OrderBase.typeKey,
                  OrderBase.typeKey.name
                )
                .tell(
                  Enable(
                    latest.data.mechineId
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
                      status = MechineStatus.qrcodeChoose
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
        case e @ CreateOrder(_) => {
          logger.info(command.logJson)
          Effect.none.thenRun((latest: State) => {
            e.replyTo.tell(
              CreateOrderFail(e, "qrcodeChoose")
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
          case e @ _ => defaultEvent(state, e)
        }
      }

    (
      commandHandler,
      defaultEvent,
      classOf[QrcodeChoose],
      MechineStatus.qrcodeChoose
    )
  }
}
