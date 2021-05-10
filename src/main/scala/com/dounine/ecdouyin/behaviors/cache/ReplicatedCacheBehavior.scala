package com.dounine.ecdouyin.behaviors.cache

import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.ddata.typed.scaladsl.Replicator._
import akka.cluster.ddata.typed.scaladsl.{DistributedData, Replicator}
import akka.cluster.ddata.{LWWMap, LWWMapKey, SelfUniqueAddress}
import com.dounine.ecdouyin.model.models.BaseSerializer

import scala.concurrent.duration.FiniteDuration

object ReplicatedCacheBehavior {

  sealed trait Command extends BaseSerializer

  final case class PutCache(key: String, value: Any, timeout: FiniteDuration)
      extends Command

  final case class GetCache(key: String)(val replyTo: ActorRef[Cached])
      extends Command

  final case class Cached(key: String, value: Option[Any])
      extends BaseSerializer

  final case class Evict(key: String) extends Command

  private sealed trait InternalCommand extends Command

  private case class InternalGetResponse(
      key: String,
      rsp: GetResponse[LWWMap[String, Any]]
  )(val replyTo: ActorRef[Cached])
      extends InternalCommand

  private case class InternalUpdateResponse(
      rsp: UpdateResponse[LWWMap[String, Any]]
  ) extends Command

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers((timers: TimerScheduler[Command]) => {
        DistributedData
          .withReplicatorMessageAdapter[Command, LWWMap[String, Any]] {
            replicator =>
              implicit val node: SelfUniqueAddress =
                DistributedData(context.system).selfUniqueAddress

              def dataKey(entryKey: String): LWWMapKey[String, Any] =
                LWWMapKey("cache-" + math.abs(entryKey.hashCode % 100))

              Behaviors.receiveMessage[Command] {
                case PutCache(key, value, timeout) =>
                  replicator.askUpdate(
                    createRequest = Update(
                      key = dataKey(key),
                      initial = LWWMap.empty[String, Any],
                      writeConsistency = Replicator.WriteLocal
                    ) {
                      _ :+ (key -> value)
                    },
                    responseAdapter = rsp => InternalUpdateResponse(rsp)
                  )
                  timers.startSingleTimer(
                    key = "cache-ttl-" + key,
                    msg = Evict(key),
                    delay = timeout
                  )
                  Behaviors.same
                case Evict(key) =>
                  replicator.askUpdate(
                    createRequest = Update(
                      key = dataKey(key),
                      initial = LWWMap.empty[String, Any],
                      writeConsistency = Replicator.WriteLocal
                    ) {
                      _.remove(node, key)
                    },
                    responseAdapter = rsp => InternalUpdateResponse(rsp)
                  )
                  Behaviors.same
                case e @ GetCache(key: String) =>
                  replicator.askGet(
                    createRequest = Get(dataKey(key), Replicator.ReadLocal),
                    responseAdapter =
                      rsp => InternalGetResponse(key, rsp)(e.replyTo)
                  )
                  Behaviors.same

                case e @ InternalGetResponse(key, g @ GetSuccess(_)) =>
                  e.replyTo.tell(Cached(key, g.dataValue.get(key)))
                  Behaviors.same

                case e @ InternalGetResponse(key, g @ NotFound(_)) =>
                  e.replyTo.tell(Cached(key, None))
                  Behaviors.same
                case _: InternalGetResponse    => Behaviors.same
                case _: InternalUpdateResponse => Behaviors.same
              }

          }
      })
    }

}
