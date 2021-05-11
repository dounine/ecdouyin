package com.dounine.ecdouyin.behaviors.order.status

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed.scaladsl.Effect
import com.dounine.ecdouyin.behaviors.mechine.MechineBase
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.tools.json.JsonParse
import org.slf4j.{Logger, LoggerFactory}
import com.dounine.ecdouyin.behaviors.order.OrderBase._
import com.dounine.ecdouyin.model.types.service.PayStatus
import com.dounine.ecdouyin.service.OrderService
import com.dounine.ecdouyin.tools.util.ServiceSingleton

import scala.util.{Failure, Success}

object ShutdowningStatus extends JsonParse {

  private final val logger: Logger =
    LoggerFactory.getLogger(ShutdowningStatus.getClass)

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
        case Run() => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              context.self.tell(Run())
            })
        }
        case MechineBase.ShutdownOk(request, id) => {
          logger.info(command.logJson)
          Effect
            .persist(command)
            .thenRun((latest: State) => {
              if (latest.data.mechines.isEmpty) {
                state.data.shutdown.foreach(_.tell(Done))
              }
            })
        }
        case default @ _ => {
          logger.warn(default.logJson)
          Effect.unhandled
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
          case Run() => {
            Stoped(
              DataStore(
                mechines = Set.empty,
                waitOrders = Map.empty,
                handOrders = Map.empty,
                lockedOrders = Set.empty,
                lockedMechines = Set.empty,
                shutdown = Option.empty
              )
            )
          }
          case MechineBase.ShutdownOk(request, id) => {
            Shutdowning(
              state.data.copy(
                mechines = state.data.mechines.filterNot(_ == id)
              )
            )
          }
        }
      }

    (commandHandler, defaultEvent, classOf[Shutdowning])
  }
}
