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
import com.dounine.ecdouyin.router.routers.{BindRouters, CachingRouter, HealthRouter}
import com.dounine.ecdouyin.service.{OrderService, UserService}
import com.dounine.ecdouyin.store.{AkkaPersistenerJournalTable, AkkaPersistenerSnapshotTable, DictionaryTable, OrderTable, UserTable}
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
    val sharding = ClusterSharding(system)
    val routers = BindRouters(system)

    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    sharding.init(
      Entity(
        typeKey = MechineBase.typeKey
      )(
        createBehavior = entityContext =>
          MechineBehavior(
            PersistenceId.of(
              MechineBase.typeKey.name,
              entityContext.entityId
            )
          )
      )
    )

    sharding.init(
      Entity(
        typeKey = QrcodeBehavior.typeKey
      )(
        createBehavior = entityContext =>
          QrcodeBehavior(
            PersistenceId.of(
              QrcodeBehavior.typeKey.name,
              entityContext.entityId
            )
          )
      )
    )

    val orderBehavior = sharding.init(
      Entity(
        typeKey = OrderBase.typeKey
      )(
        createBehavior = entityContext =>
          OrderBehavior(
            PersistenceId.of(
              OrderBase.typeKey.name,
              entityContext.entityId
            )
          )
      )
    )

    val cluster: Cluster = Cluster.get(system)
    val managementRoutes: Route = ClusterHttpManagementRoutes(cluster)

    ServiceSingleton.put(classOf[OrderService], new OrderService(system))
    ServiceSingleton.put(classOf[UserService], new UserService(system))
    import slick.jdbc.MySQLProfile.api._
    val db = DataSource(system).source().db
    val schemas = Seq(
      lifted.TableQuery[UserTable].schema,
      lifted.TableQuery[OrderTable].schema,
      lifted.TableQuery[DictionaryTable].schema,
      lifted.TableQuery[AkkaPersistenerJournalTable].schema,
      lifted.TableQuery[AkkaPersistenerSnapshotTable].schema
    )
    schemas.foreach(schema => {
      try {
        Await.result(
          db.run(schema.createIfNotExists),
          Duration.Inf
        )
      } catch {
        case e =>
      }
    })
    ServiceSingleton
      .get(classOf[UserService])
      .add(
        UserModel.DbInfo(
          apiKey = "hello",
          apiSecret = "abc",
          balance = BigDecimal("0.00"),
          margin = BigDecimal("0.00"),
          createTime = LocalDateTime.now()
        )
      )

    CoordinatedShutdown(system)
      .addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "task") { () =>
        {
          sharding
            .entityRefFor(
              OrderBase.typeKey,
              OrderBase.typeKey.name
            )
            .ask(OrderBase.Shutdown()(_))(3.seconds)
        }
      }

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
          orderBehavior.tell(
            ShardingEnvelope(
              OrderBase.typeKey.name,
              OrderBase.Run()
            )
          )
      })

  }

}
