package com.dounine.ecdouyin

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.Cluster
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.management.scaladsl.AkkaManagement
import akka.stream.SystemMaterializer
import com.dounine.ecdouyin.router.routers._
import com.dounine.ecdouyin.shutdown.Shutdowns
import com.dounine.ecdouyin.startup.Startups
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}
object EcDouyin {
  private val logger = LoggerFactory.getLogger(EcDouyin.getClass)

  def main(args: Array[String]): Unit = {

    implicit val system = ActorSystem(Behaviors.empty, "ecdouyin")
    val config = system.settings.config.getConfig("app")
    val appName = config.getString("name")
    implicit val materialize = SystemMaterializer(system).materializer
    implicit val executionContext = system.executionContext

    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    val cluster: Cluster = Cluster.get(system)
    val managementRoutes: Route = ClusterHttpManagementRoutes(cluster)

    val startup = new Startups(system)
    startup.start()
    new Shutdowns(system).listener()
    val routers = Array(
      new HealthRouter(system).route,
      new WebsocketRouter(system).route,
      new FileRouter(system).route,
      new OrderRouter(system).route,
      new GraphqlRouter(system).route
    )

    Http(system)
      .newServerAt(
        interface = config.getString("server.host"),
        port = config.getInt("server.port")
      )
      .bind(concat(BindRouters(system, routers), managementRoutes))
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
