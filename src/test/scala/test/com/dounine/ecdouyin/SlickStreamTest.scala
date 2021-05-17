package test.com.dounine.ecdouyin

import akka.Done
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.stream.Materializer
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.dounine.ecdouyin.model.models.{OrderModel, UserModel}
import com.dounine.ecdouyin.model.types.service.{PayPlatform, PayStatus}
import com.dounine.ecdouyin.service.OrderStream
import com.dounine.ecdouyin.store.{EnumMappers, OrderTable, UserTable}
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import slick.dbio.Effect
import slick.sql.{FixedSqlAction, FixedSqlStreamingAction}

import java.time.LocalDateTime
import scala.concurrent.Future

class SlickStreamTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25521
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          SlickStreamTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          SlickStreamTest
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
    with MockitoSugar {

  implicit val ec = system.executionContext
  implicit val materializer = Materializer(system)
  val db = DataSource(system).source().db
  implicit val slickSession =
    SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)

  "slick stream optimize" should {

    "return id" in {
      import slickSession.profile.api._
      val orderTable = TableQuery[OrderTable]
      val insertAndGetId =
        orderTable returning orderTable.map(_.orderId) into ((item, id) => id)

      val order = OrderModel.DbInfo(
        orderId = 0,
        outOrder = "5",
        apiKey = "lake",
        account = "abc",
        money = 6,
        volumn = 60,
        margin = BigDecimal(6),
        payCount = 0,
        platform = PayPlatform.douyin,
        status = PayStatus.normal,
        createTime = LocalDateTime.now()
      )

      info(Source.single(order)
        .via(OrderStream.createOrderMarginUser(system))
        .runWith(Sink.head)
        .futureValue.toString)


//      val cc = Source.single(order)
//        .via(Slick.flowWithPassThrough(o => {
//          (for{
//            id <- insertAndGetId += o
//
//          } yield o.copy(orderId = id)).transactionally
//        }))
//        .runWith(Sink.head)
//        .futureValue
//
//      info(cc.toString)

//      val insert = slickSession.db.run(
//        (for{
//          id <- insertAndGetId += order
//        } yield id).transactionally
//      )
//
//      info(insert.futureValue.toString)


    }

    "init" ignore {
      import slickSession.profile.api._
      val insertFun: UserModel.DbInfo => DBIO[Int] =
        (userInfo: UserModel.DbInfo) => {
          TableQuery[UserTable] += UserModel.DbInfo(
            apiKey = userInfo.apiKey,
            apiSecret = userInfo.apiSecret,
            balance = userInfo.balance,
            margin = userInfo.margin,
            callback = userInfo.callback,
            createTime = userInfo.createTime
          )
        }

      Slick
        .source(TableQuery[UserTable].result)
        .via(Slick.flow((i: UserModel.DbInfo) => {
          List
            .fill(3)(i)
            .map(insertFun)
            .reduceLeft(_.andThen(_))
        }))
        .runWith(Sink.ignore)
        .futureValue shouldBe Done
//        .runForeach(result => info(result.toString))
//        .futureValue shouldBe Done
    }

  }
}
