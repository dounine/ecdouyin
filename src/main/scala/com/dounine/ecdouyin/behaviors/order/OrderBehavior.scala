package com.dounine.ecdouyin.behaviors.order

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  RetentionCriteria
}
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.tools.json.JsonParse
import org.slf4j.LoggerFactory
import OrderBase._
import scala.concurrent.duration._

object OrderBehavior extends JsonParse {
  private val logger = LoggerFactory.getLogger(OrderBehavior.getClass)

  def apply(
      entityId: PersistenceId
  ): Behavior[BaseSerializer] =
    Behaviors.setup { context: ActorContext[BaseSerializer] =>
      Behaviors.withTimers((timers: TimerScheduler[BaseSerializer]) => {
        val statusList = Seq(
          status.StopedStatus(context, timers),
          status.BusyStatus(context, timers),
          status.IdleStatus(context, timers),
          status.ShutdowningStatus(context, timers)
        )

        val commandDefaultHandler: (
            State,
            BaseSerializer
        ) => Effect[BaseSerializer, State] = (state, command) =>
          command match {
            case Shutdown() => {
              logger.info(command.logJson)
              Effect.none.thenStop()
            }
          }

        def eventDefaultHandler(
            state: State,
            command: BaseSerializer
        ): State = {
          command match {
            case _ => throw new Exception(s"unknow command ${command}")
          }
        }

        val commandHandler: (
            State,
            BaseSerializer
        ) => Effect[BaseSerializer, State] = (state, command) =>
          statusList.find(_._3 == state.getClass) match {
            case Some(status) =>
              status._1(state, command, commandDefaultHandler)
            case None =>
              throw new Exception(s"status unknown -> ${state.getClass}")
          }

        val eventHandler: (State, BaseSerializer) => State =
          (state, command) =>
            statusList.find(_._3 == state.getClass) match {
              case Some(status) =>
                status._2(state, command, eventDefaultHandler)
              case None => throw new Exception("status unknown")
            }

        EventSourcedBehavior(
          persistenceId = entityId,
          emptyState = Stoped(
            data = DataStore(
              mechines = Set.empty,
              waitOrders = Map.empty,
              handOrders = Map.empty,
              shutdown = Option.empty
            )
          ),
          commandHandler = commandHandler,
          eventHandler = eventHandler
        ).onPersistFailure(
            backoffStrategy = SupervisorStrategy
              .restartWithBackoff(
                minBackoff = 1.seconds,
                maxBackoff = 10.seconds,
                randomFactor = 0.2
              )
              .withMaxRestarts(maxRestarts = 3)
              .withResetBackoffAfter(10.seconds)
          )
          .receiveSignal({
            case (state, RecoveryCompleted) =>
              logger.debug(
                "Recovery Completed with state: {}",
                state
              )
            case (state, RecoveryFailed(err)) =>
              logger.error(
                "Recovery failed with: {}",
                err.getMessage
              )
            case (state, SnapshotCompleted(meta)) =>
              logger.debug(
                "Snapshot Completed with state: {},id({},{})",
                state,
                meta.persistenceId,
                meta.sequenceNr
              )
            case (state, SnapshotFailed(meta, err)) =>
              logger.error(
                "Snapshot failed with: {}",
                err.getMessage
              )
            case (_, PreRestart) =>
              logger.info(s"PreRestart")
//              context.self.tell(Recovery())
            case (_, single) =>
              logger.debug(single.logJson)
          })
          .snapshotWhen((state, event, _) => true)
          .withRetention(
            criteria = RetentionCriteria
              .snapshotEvery(numberOfEvents = 2, keepNSnapshots = 1)
              .withDeleteEventsOnSnapshot
          )

      })

    }

}
