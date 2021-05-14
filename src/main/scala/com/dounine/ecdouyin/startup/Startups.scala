package com.dounine.ecdouyin.startup

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.persistence.typed.PersistenceId
import com.dounine.ecdouyin.behaviors.engine.{CoreEngine, OrderSources}
import com.dounine.ecdouyin.model.models.UserModel
import com.dounine.ecdouyin.service.{
  DictionaryService,
  OrderService,
  UserService
}
import com.dounine.ecdouyin.store.{
  AkkaPersistenerJournalTable,
  AkkaPersistenerSnapshotTable,
  DictionaryTable,
  OrderTable,
  UserTable
}
import com.dounine.ecdouyin.tools.akka.chrome.ChromePools
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.dounine.ecdouyin.tools.util.ServiceSingleton
import org.slf4j.LoggerFactory
import slick.lifted

import java.time.LocalDateTime
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class Startups(system: ActorSystem[_]) {
  private val logger = LoggerFactory.getLogger(classOf[Startups])
  implicit val ec = system.executionContext
  val sharding = ClusterSharding(system)

  def start(): Unit = {
    sharding.init(
      Entity(
        typeKey = CoreEngine.typeKey
      )(
        createBehavior = entityContext =>
          CoreEngine(
            PersistenceId.of(
              CoreEngine.typeKey.name,
              entityContext.entityId
            )
          )
      )
    )

    ServiceSingleton.put(classOf[OrderService], new OrderService(system))
    ServiceSingleton.put(classOf[UserService], new UserService(system))
    ServiceSingleton.put(
      classOf[DictionaryService],
      new DictionaryService(system)
    )
//    ChromePools(system).pools
//      .returnObject(ChromePools(system).pools.borrowObject())

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
          balance = BigDecimal("10.00"),
          margin = BigDecimal("0.00"),
          callback = Option.empty,
          createTime = LocalDateTime.now()
        )
      )
      .onComplete {
        case Failure(exception) => {
          logger.error(exception.getMessage)
        }
        case Success(value) =>
          logger.info(s"insert user apikey result ${value}")
      }

  }

  def httpAfter(): Unit = {
    sharding
      .entityRefFor(
        CoreEngine.typeKey,
        CoreEngine.typeKey.name
      )
      .ask(
        CoreEngine.Message(
          OrderSources.QueryOrderInit()
        )
      )(3.seconds)
  }

}
