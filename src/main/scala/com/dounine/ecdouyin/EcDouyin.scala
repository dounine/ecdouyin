package com.dounine.ecdouyin

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.Cluster
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.management.scaladsl.AkkaManagement
import akka.persistence.typed.PersistenceId
import akka.stream.SystemMaterializer
import com.dounine.ecdouyin.behaviors.mechine.{MechineBase, MechineBehavior}
import com.dounine.ecdouyin.behaviors.order.{OrderBase, OrderBehavior}
import com.dounine.ecdouyin.behaviors.qrcode.QrcodeBehavior
import com.dounine.ecdouyin.model.models.{OrderModel, UserModel}
import com.dounine.ecdouyin.router.routers.{
  BindRouters,
  CachingRouter,
  HealthRouter
}
import com.dounine.ecdouyin.service.{OrderService, UserService}
import com.dounine.ecdouyin.shutdown.Shutdowns
import com.dounine.ecdouyin.startup.Startups
import com.dounine.ecdouyin.store.{
  AkkaPersistenerJournalTable,
  AkkaPersistenerSnapshotTable,
  DictionaryTable,
  OrderTable,
  UserTable
}
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.dounine.ecdouyin.tools.util.ServiceSingleton
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import slick.lifted
import slick.lifted.TableQuery

import java.time.LocalDateTime
import java.util.Dictionary
import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._
object EcDouyin {
  private val logger = LoggerFactory.getLogger(EcDouyin.getClass)

  def main(args: Array[String]): Unit = {

    implicit val system = ActorSystem(Behaviors.empty, "ecdouyin")
    val config = system.settings.config.getConfig("app")
    val appName = config.getString("name")
    implicit val materialize = SystemMaterializer(system).materializer
    implicit val executionContext = system.executionContext
    val routers = BindRouters(system)

    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    val cluster: Cluster = Cluster.get(system)
    val managementRoutes: Route = ClusterHttpManagementRoutes(cluster)

    val startup = new Startups(system)
    startup.start()
    new Shutdowns(system).listener()

    Http(system)
      .newServerAt(
        interface = config.getString("server.host"),
        port = config.getInt("server.port")
      )
      .bind(concat(routers, managementRoutes))
      .onComplete({
        case Failure(exception) => throw exception
        case Success(value) =>
          logger.info(
            s"""${appName} server http://${value.localAddress.getHostName}:${value.localAddress.getPort} running"""
          )
          startup.httpAfter()
      })

  }

}
