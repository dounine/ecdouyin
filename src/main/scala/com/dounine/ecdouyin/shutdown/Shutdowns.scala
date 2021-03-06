package com.dounine.ecdouyin.shutdown

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.dounine.ecdouyin.behaviors.engine.CoreEngine
import com.dounine.ecdouyin.tools.akka.chrome.ChromePools
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.dounine.ecdouyin.tools.util.DingDing
import org.joda.time.LocalDateTime
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
class Shutdowns(system: ActorSystem[_]) {
  implicit val ec = system.executionContext
  val sharding = ClusterSharding(system)
  val logger = LoggerFactory.getLogger(classOf[Shutdowns])

  def listener(): Unit = {
    CoordinatedShutdown(system)
      .addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "closeOrder") {
        () =>
          {
            logger.info("orderClose")
            sharding
              .entityRefFor(
                CoreEngine.typeKey,
                CoreEngine.typeKey.name
              )
              .ask(CoreEngine.Shutdown())(3.seconds)
          }
      }

    CoordinatedShutdown(system)
      .addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "closeDb") { () =>
        {
          Future {
            logger.info("db source close")
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
              logger.info("chrome source close")
              ChromePools(system).pools.close()
              Done
            }
          }
      }

    CoordinatedShutdown(system).addJvmShutdownHook(() => {
      DingDing.sendMessage(
        DingDing.MessageType.system,
        data = DingDing.MessageData(
          markdown = DingDing.Markdown(
            title = "系统通知",
            text = s"""
                |# 程序停止
                | - time: ${LocalDateTime.now()}
                |""".stripMargin
          )
        ),
        system
      )

    })

  }

}
