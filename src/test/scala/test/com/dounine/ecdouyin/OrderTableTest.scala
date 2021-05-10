package test.com.dounine.ecdouyin

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.dounine.ecdouyin.model.models.{OrderModel, UserModel}
import com.dounine.ecdouyin.model.types.service.{
  MechinePayStatus,
  PayPlatform,
  PayStatus
}
import com.dounine.ecdouyin.service.{OrderService, UserService}
import com.dounine.ecdouyin.store.{EnumMappers, OrderTable, UserTable}
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.dounine.ecdouyin.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import java.time.LocalDateTime
import scala.concurrent.Await
import scala.concurrent.duration._

class OrderTableTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.remote.artery.canonical.port = 25520
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          OrderTableTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          OrderTableTest
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
  val dict = TableQuery[OrderTable]

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

  val orderService = new OrderService(system)
  "order table test" should {
    "table create" in {
      beforeFun()
      val info = OrderModel.DbInfo(
        orderId = 1,
        outOrder = "1",
        apiKey = "abc",
        account = "abc",
        money = 6,
        volumn = 60,
        margin = BigDecimal(6),
        payCount = 0,
        platform = PayPlatform.douyin,
        status = PayStatus.normal,
        mechineStatus = MechinePayStatus.normal,
        createTime = LocalDateTime.now()
      )

      orderService
        .add(
          info
        )
        .futureValue shouldBe Option(1)
      orderService.infoOrder(info.orderId).futureValue shouldBe Option(info)
      afterFun()
    }
  }
}
