package com.dounine.ecdouyin.tools.akka.cache

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout
import com.dounine.ecdouyin.behaviors.cache.ReplicatedCacheBehavior
import org.json4s.Formats
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

class Cache(system: ActorSystem[_]) {

  private val replicatedCacheBehavior =
    system.systemActorOf(ReplicatedCacheBehavior(), "ReplicatedCacheBehavior")
  private val materializer = SystemMaterializer(system).materializer

  def upcache[T: Manifest](
      key: String,
      ttl: FiniteDuration,
      default: () => Future[T],
      cache: Boolean = true
  )(implicit formats: Formats): Future[T] = {
    Source
      .single(key)
      .via(
        ActorFlow.ask(replicatedCacheBehavior)(
          (el: String, replyTo: ActorRef[ReplicatedCacheBehavior.Cached]) =>
            ReplicatedCacheBehavior.GetCache(el)(replyTo)
        )(Timeout(3.seconds))
      )
      .recover {
        case e: Throwable => {
          e.printStackTrace()
          ReplicatedCacheBehavior.Cached(key, Option.empty)
        }
      }
      .runWith(Sink.head)(materializer)
      .flatMap(result => {
        result.value match {
          case Some(value) => Future.successful(value.asInstanceOf[T])
          case None =>
            default()
              .map(f => {
                replicatedCacheBehavior
                  .tell(ReplicatedCacheBehavior.PutCache(key, f, ttl))
                f
              })(system.executionContext)
        }
      })(system.executionContext)
  }

  def update(): Unit = {}

}
