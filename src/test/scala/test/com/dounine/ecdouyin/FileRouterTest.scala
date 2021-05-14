package test.com.dounine.ecdouyin

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, MediaTypes, Multipart, RequestEntity}
import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.management.scaladsl.AkkaManagement
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import com.dounine.ecdouyin.EcDouyin.logger
import com.dounine.ecdouyin.router.routers.{BindRouters, FileRouter, HealthRouter, OrderRouter, WebsocketRouter}
import com.dounine.ecdouyin.service.OrderService
import com.dounine.ecdouyin.store.EnumMappers
import com.dounine.ecdouyin.tools.akka.ConnectSettings
import com.dounine.ecdouyin.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.apache.commons.codec.binary.Base64
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.io.File
import java.nio.file.{Files, Paths}
import java.time.LocalDateTime
import scala.concurrent.Future
import scala.util.{Failure, Success}

class FileRouterTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.remote.artery.canonical.port = 25520
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          FileRouterTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          FileRouterTest
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

  override protected def beforeAll(): Unit = {
    val cluster = Cluster.get(system)
    cluster.manager.tell(Join.create(cluster.selfMember.address))
    val routers = Array(
      new HealthRouter(system).route,
      new WebsocketRouter(system).route,
      new FileRouter(system).route,
      new OrderRouter(system).route
    )

    val config = system.settings.config.getConfig("app")
    val appName = config.getString("name")
    val managementRoutes: Route = ClusterHttpManagementRoutes(
      akka.cluster.Cluster(system)
    )
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()
    Http(system)
      .newServerAt(
        interface = config.getString("server.host"),
        port = config.getInt("server.port")
      )
      .bind(concat(BindRouters(system,routers), managementRoutes))
      .onComplete({
        case Failure(exception) => throw exception
        case Success(value) =>
          info(
            s"""${appName} server http://${value.localAddress.getHostName}:${value.localAddress.getPort} running"""
          )
      })(system.executionContext)
  }

  val http = Http(system)
  "file router test" should {
    "upload png" in {
      val port = system.settings.config.getInt("app.server.port")
      val file = new File("/tmp/wechat.png")
      val response = Source
        .future(
          FileIO
            .fromPath(Paths.get(file.getAbsolutePath))
            .map(f => f)
            .runWith(Sink.head)
        )
        .mapAsync(1) { fileByteString =>
          {
            val body = Multipart.FormData.fromFile(
              name = "file",
              contentType = MediaTypes.`application/octet-stream`,
              file = file,
              chunkSize = -1
            )
            http
              .singleRequest(
                HttpRequest(
                  uri = s"http://localhost:${port}/file/image",
                  method = HttpMethods.POST,
                  entity = body.toEntity()
                ),
                settings = ConnectSettings.httpSettings(system)
              )
              .flatMap {
                case HttpResponse(_, _, entity, _) =>
                  entity.dataBytes
                    .runFold(ByteString.empty)(_ ++ _)
                    .map(_.utf8String)(system.executionContext)
                    .map(_.jsonTo[Map[String, Any]])(system.executionContext)
                case msg @ _ =>
                  Future.failed(new Exception(s"请求失败 $msg"))
              }(system.executionContext)
          }
        }
        .recover {
          case e: Throwable => {
            throw e
          }
        }
        .runWith(Sink.head)
        .futureValue

      info(response.toString())
//
//      val downloadUrl = response("data")
//        .asInstanceOf[Map[String, Any]]
//        .map(i => (i._1, i._2.toString))("domain") + response("data")
//        .asInstanceOf[Map[String, Any]]
//        .map(i => (i._1, i._2.toString))("url")
//
//      info(downloadUrl)
//
//      val tmpFile = Files.createTempFile("test", "png")
//
//      val downloadResponse = Http(system)
//        .singleRequest(
//          HttpRequest(
//            method = HttpMethods.GET,
//            uri = downloadUrl
//          )
//        )
//        .flatMap {
//          case HttpResponse(_, _, entity, _) =>
//            entity.dataBytes.runWith(FileIO.toPath(tmpFile))
//          case msg @ _ =>
//            Future.failed(new Exception(s"请求失败 $msg"))
//        }(system.executionContext)
//        .futureValue
//
//      info(downloadResponse.count.toString)

    }
  }
}
