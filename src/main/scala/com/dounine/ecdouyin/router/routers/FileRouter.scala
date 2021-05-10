package com.dounine.ecdouyin.router.routers

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{
  ContentType,
  HttpEntity,
  HttpResponse,
  MediaTypes
}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileInfo
import akka.stream.scaladsl.{FileIO, Flow, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{CompletionStrategy, _}
import akka.{NotUsed, actor}
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import java.nio.file.{Files, Paths}
import java.time.LocalDate
import scala.concurrent.{ExecutionContextExecutor, Future}

class FileRouter(system: ActorSystem[_]) extends SuportRouter {

  private final val logger: Logger =
    LoggerFactory.getLogger(classOf[FileRouter])
  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val actorSystem: actor.ActorSystem = materializer.system
  implicit val executionContext: ExecutionContextExecutor =
    materializer.executionContext

  val fileSaveDirectory = system.settings.config.getString("app.file.directory")
  val domain = system.settings.config.getString("app.file.domain")

  def tempDestination(fileInfo: FileInfo): File = {
    val directory = new File(fileSaveDirectory + "/" + LocalDate.now())
    directory.mkdirs()
    File.createTempFile(
      "screen_",
      "_" + fileInfo.getFileName,
      directory
    )
  }

  val route: Route = {
    concat(
      post {
        path("file" / "image") {
          storeUploadedFile("file", tempDestination) {
            case (metadata, file) => {
              ok(
                Map(
                  "domain" -> (domain + "/file/image?path="),
                  "url" -> file.getAbsolutePath
                )
              )
            }
          }
        }
      },
      get {
        path("file" / "image") {
          parameter("path") { path =>
            {
              val byteArray: Array[Byte] = Files.readAllBytes(Paths.get(path))
              complete(
                HttpResponse(entity =
                  HttpEntity(ContentType(MediaTypes.`image/png`), byteArray)
                )
              )
            }
          }
        }
      }
    )
  }

}
