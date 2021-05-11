package com.dounine.ecdouyin.shutdown

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.dounine.ecdouyin.behaviors.order.OrderBase
import com.dounine.ecdouyin.tools.akka.chrome.ChromePools
import com.dounine.ecdouyin.tools.akka.db.DataSource

import scala.concurrent.Future
import scala.concurrent.duration._
class Shutdowns(system: ActorSystem[_]) {
  implicit val ec = system.executionContext
  val sharding = ClusterSharding(system)

  def listener(): Unit = {
    CoordinatedShutdown(system)
      .addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "closeOrder") {
        () =>
          {
            sharding
              .entityRefFor(
                OrderBase.typeKey,
                OrderBase.typeKey.name
              )
              .ask(OrderBase.Shutdown()(_))(3.seconds)
          }
      }

    CoordinatedShutdown(system)
      .addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "closeDb") { () =>
        {
          Future {
            DataSource(system)
              .source()
              .db
              .close()
            Done
          }
        }
      }

    CoordinatedShutdown(system)
      .addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "closeChrome") {
        () =>
          {
            Future {
              ChromePools(system).pools.close()
              Done
            }
          }
      }

  }

}
