package test.com.dounine.ecdouyin

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.persistence.typed.PersistenceId
import akka.stream.alpakka.udp.Datagram
import akka.stream.alpakka.udp.scaladsl.Udp
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.dounine.ecdouyin.model.models.{StockTimeSerieModel, UserModel}
import com.dounine.ecdouyin.model.types.service.IntervalStatus
import com.dounine.ecdouyin.service.UserService
import com.dounine.ecdouyin.store.{EnumMappers, UserTable}
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.dounine.ecdouyin.tools.json.JsonParse
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s._
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import slick.lifted.TableQuery
import slick.jdbc.MySQLProfile.api._

import java.net.InetSocketAddress
import scala.concurrent.duration._
import java.time.{Instant, LocalDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Future}
import scala.util.{Success, Try}

class UserTableTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.remote.artery.canonical.port = 25520
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          UserTableTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          UserTableTest
        ].getSimpleName}"
           |""".stripMargin)
        .withFallback(
          ConfigFactory.parseResources("application-test.conf")
        )
        .resolve()
    )
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with EnumMappers
    with MockitoSugar
    with JsonParse {
  val sharding = ClusterSharding(system)

  val db = DataSource(system).source().db
  val dict = TableQuery[UserTable]

  def beforeFun(): Unit = {
    try {
      Await.result(db.run(dict.schema.dropIfExists), Duration.Inf)
    } catch {
      case e =>
    }
    Await.result(db.run(dict.schema.createIfNotExists), Duration.Inf)
  }

  def afterFun(): Unit = {
    Await.result(db.run(dict.schema.truncate), Duration.Inf)
    Await.result(db.run(dict.schema.dropIfExists), Duration.Inf)
  }

  val userService = new UserService(system)
  "user table test" should {
    "table create" in {
      beforeFun()
      val info = UserModel.DbInfo(
        apiKey = "abc",
        apiSecret = "abc",
        balance = BigDecimal("0.00"),
        margin = BigDecimal("0.00"),
        callback = Option.empty,
        createTime = LocalDateTime.now()
      )

      userService
        .add(
          info
        )
        .futureValue shouldBe Option(1)
      userService.info(apiKey = info.apiKey).futureValue shouldBe Option(info)
      afterFun()
    }
  }
}
