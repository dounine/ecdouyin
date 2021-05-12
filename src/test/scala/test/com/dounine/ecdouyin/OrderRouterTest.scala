package test.com.dounine.ecdouyin

import akka.actor.CoordinatedShutdown
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.management.scaladsl.AkkaManagement
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import com.dounine.ecdouyin.behaviors.mechine.{MechineBase, MechineBehavior}
import com.dounine.ecdouyin.behaviors.order.{OrderBase, OrderBehavior}
import com.dounine.ecdouyin.behaviors.qrcode.QrcodeBehavior
import com.dounine.ecdouyin.model.models.{OrderModel, UserModel}
import com.dounine.ecdouyin.model.types.service.PayPlatform
import com.dounine.ecdouyin.router.routers.{BindRouters, FileRouter, HealthRouter, OrderRouter, WebsocketRouter}
import com.dounine.ecdouyin.service.{OrderService, UserService}
import com.dounine.ecdouyin.store.{AkkaPersistenerJournalTable, AkkaPersistenerSnapshotTable, DictionaryTable, EnumMappers, OrderTable, UserTable}
import com.dounine.ecdouyin.tools.akka.ConnectSettings
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.dounine.ecdouyin.tools.json.JsonParse
import com.dounine.ecdouyin.tools.util.{MD5Util, ServiceSingleton}
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import slick.lifted
import slick.jdbc.MySQLProfile.api._

import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

class OrderRouterTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.remote.artery.canonical.port = 25520
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          OrderRouterTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          OrderRouterTest
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
  protected override def afterAll(): Unit = {
    import scala.concurrent.duration._
    Await.result(
      sharding
        .entityRefFor(
          OrderBase.typeKey,
          OrderBase.typeKey.name
        )
        .ask(OrderBase.Shutdown()(_))(3.seconds),
      Duration.Inf
    )
    db.close()
    super.afterAll()
  }

  val schemas = Seq(
    lifted.TableQuery[UserTable].schema,
    lifted.TableQuery[OrderTable].schema,
    lifted.TableQuery[DictionaryTable].schema,
    lifted.TableQuery[AkkaPersistenerJournalTable].schema,
    lifted.TableQuery[AkkaPersistenerSnapshotTable].schema
  )
  val db = DataSource(system).source().db
  override protected def beforeAll(): Unit = {
    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))

    val config = system.settings.config.getConfig("app")
    val appName = config.getString("name")
    val managementRoutes: Route = ClusterHttpManagementRoutes(
      akka.cluster.Cluster(system)
    )
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    ServiceSingleton.put(classOf[OrderService], new OrderService(system))
    ServiceSingleton.put(classOf[UserService], new UserService(system))
    val routers = Array(
      new HealthRouter(system).route,
      new WebsocketRouter(system).route,
      new FileRouter(system).route,
      new OrderRouter(system).route
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

    Http(system)
      .newServerAt(
        interface = config.getString("server.host"),
        port = config.getInt("server.port")
      )
      .bind(concat(BindRouters(system,routers), managementRoutes))
      .onComplete({
        case Failure(exception) => throw exception
        case Success(value) =>
          orderBehavior.tell(
            ShardingEnvelope(
              OrderBase.typeKey.name,
              OrderBase.Run()
            )
          )
          println(
            s"""${appName} server http://${value.localAddress.getHostName}:${value.localAddress.getPort} running"""
          )
      })(system.executionContext)
  }

  def beforeWorking(): Unit = {
    schemas.foreach(schema => {
      try {
        Await.result(db.run(schema.dropIfExists), Duration.Inf)
      } catch {
        case e =>
      }
      Await.result(db.run(schema.createIfNotExists), Duration.Inf)
    })
  }

  def afterWorking(): Unit = {
    schemas.foreach(schema => {
      Await.result(db.run(schema.truncate), Duration.Inf)
      Await.result(db.run(schema.dropIfExists), Duration.Inf)
    })
  }

  val http = Http(system)
  "order router test" should {
    "create" in {
      beforeWorking()
      val userService = ServiceSingleton
        .get(classOf[UserService])

      val userInfo = UserModel.DbInfo(
        apiKey = "hello",
        apiSecret = "abc",
        balance = BigDecimal("10.00"),
        margin = BigDecimal("0.00"),
        callback = Option.empty,
        createTime = LocalDateTime.now()
      )
      userService.add(
        userInfo
      )

      val port = system.settings.config.getInt("app.server.port")
      val order = OrderModel.Recharge(
        apiKey = userInfo.apiKey,
        account = "123",
        money = "1",
        platform = PayPlatform.douyin,
        outOrder = "1",
        sign = ""
      )

      val createSource = Source
        .future(
          userService.info(order.apiKey)
        )
        .flatMapConcat {
          case Some(info) =>
            Source.future(
              http
                .singleRequest(
                  request = HttpRequest(
                    method = HttpMethods.POST,
                    uri = s"http://localhost:${port}/order/recharge",
                    entity = HttpEntity(
                      ContentTypes.`application/json`,
                      order
                        .copy(
                          sign = MD5Util.md5(
                            info.apiSecret + order.account + order.money + order.platform + order.outOrder
                          )
                        )
                        .toJson
                    )
                  )
                )
                .flatMap {
                  case HttpResponse(_, _, entity, _) =>
                    entity.dataBytes
                      .runFold(ByteString.empty)(_ ++ _)
                      .map(_.utf8String)(system.executionContext)
                  case msg @ _ =>
                    Future.failed(new Exception(s"请求失败 $msg"))
                }(system.executionContext)
            )
          case None => throw new Exception("apiKey not exit")
        }

      val createResponseStr = createSource
        .runWith(Sink.head)
        .futureValue
      val dataResponse = createResponseStr
        .jsonTo[Map[String, Any]]
        .get("data")
        .map(_.asInstanceOf[Map[String, String]])

      info(createResponseStr)
      dataResponse.map(_("orderId")) shouldBe Option("1")

      dataResponse
        .map(_("orderId"))
        .foreach({ orderId =>
          {
            val query = OrderModel.Query(
              apiKey = userInfo.apiKey,
              orderId = Option(orderId),
              outOrder = None,
              sign = ""
            )
            val querySource = Source
              .future(
                userService.info(order.apiKey)
              )
              .flatMapConcat {
                case Some(info) =>
                  Source.future(
                    http
                      .singleRequest(
                        request = HttpRequest(
                          method = HttpMethods.GET,
                          uri =
                            s"http://localhost:${port}/order/info?apiKey=${info.apiKey}&orderId=${query.orderId
                              .getOrElse("")}&sign=${MD5Util.md5(
                              info.apiSecret + query.orderId.getOrElse("")
                            )}"
                        )
                      )
                      .flatMap {
                        case HttpResponse(_, _, entity, _) =>
                          entity.dataBytes
                            .runFold(ByteString.empty)(_ ++ _)
                            .map(_.utf8String)(system.executionContext)
                        case msg @ _ =>
                          Future.failed(new Exception(s"请求失败 $msg"))
                      }(system.executionContext)
                  )
                case None => throw new Exception("apiKey not exit")
              }

            val queryResponseStr = querySource.runWith(Sink.head).futureValue
            info(queryResponseStr)

            queryResponseStr
              .jsonTo[Map[String, Any]]
              .get("code")
              .map(_.toString) shouldBe Option("ok")
          }
        })

      dataResponse
        .map(_("orderId"))
        .foreach({ orderId =>
          val cancel = OrderModel.Cancel(
            apiKey = order.apiKey,
            orderId = dataResponse.map(_("orderId")),
            outOrder = None,
            sign = ""
          )
          val cancelSource = Source
            .future(
              ServiceSingleton.get(classOf[UserService]).info(order.apiKey)
            )
            .flatMapConcat {
              case Some(info) =>
                Source.future(
                  http
                    .singleRequest(
                      request = HttpRequest(
                        method = HttpMethods.POST,
                        uri = s"http://localhost:${port}/order/cancel",
                        entity = HttpEntity(
                          ContentTypes.`application/json`,
                          cancel
                            .copy(
                              sign = MD5Util.md5(
                                info.apiSecret + orderId
                              )
                            )
                            .toJson
                        )
                      )
                    )
                    .flatMap {
                      case HttpResponse(_, _, entity, _) =>
                        entity.dataBytes
                          .runFold(ByteString.empty)(_ ++ _)
                          .map(_.utf8String)(system.executionContext)
                      case msg @ _ =>
                        Future.failed(new Exception(s"请求失败 $msg"))
                    }(system.executionContext)
                )
              case None => throw new Exception("apiKey not exit")
            }

          val cancelResponseStr = cancelSource.runWith(Sink.head).futureValue
          val cancelResponse = cancelResponseStr
            .jsonTo[Map[String, Any]]

          info(cancelResponseStr)
          cancelResponse.get("code") shouldBe Option("ok")
        })

      afterWorking()
    }
  }
}
