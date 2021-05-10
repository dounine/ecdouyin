package com.dounine.ecdouyin.behaviors.mechine.status

import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.ecdouyin.behaviors.mechine.MechineBase._
import com.dounine.ecdouyin.behaviors.mechine.status.QrcodeChooseStatus.logger
import com.dounine.ecdouyin.behaviors.order.OrderBase
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.model.types.service.MechineStatus
import com.dounine.ecdouyin.model.types.service.MechineStatus.MechineStatus
import com.dounine.ecdouyin.tools.json.JsonParse
import org.slf4j.{Logger, LoggerFactory}

object DisconnectedStatus extends JsonParse {

  private final val logger: Logger =
    LoggerFactory.getLogger(DisconnectedStatus.getClass)

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
        case e @ CreateOrder(_) => {
          logger.info(command.logJson)
          Effect.none.thenRun((latest: State) => {
            e.replyTo.tell(
              CreateOrderFail(e, "disconnected")
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
      classOf[Disconnected],
      MechineStatus.disconnected
    )
  }
}
