package com.dounine.ecdouyin.tools.util

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes
}
import akka.stream.Materializer
import akka.util.ByteString
import com.dounine.ecdouyin.tools.json.JsonParse
import com.dounine.ecdouyin.tools.util.DingDing.MessageType.MessageType
import org.slf4j.LoggerFactory

import scala.concurrent.Future

object DingDing extends JsonParse {

  private val logger = LoggerFactory.getLogger(DingDing.getClass)
  object MessageType extends Enumeration {
    type MessageType = Value
    val system = Value("system_notify")
    val app = Value("app_notify")
    val order = Value("order_notify")
    val payed = Value("payed_notify")
    val payerr = Value("payerr_notify")
  }
  case class Markdown(
      title: String,
      text: String
  )
  case class MessageData(
      markdown: Markdown,
      msgtype: String = "markdown"
  )
  def sendMessage(
      mType: MessageType,
      data: MessageData,
      system: ActorSystem[_]
  ): Unit = {
    implicit val ec = system.executionContext
    implicit val materializer = Materializer(system)
    val http = Http(system)
    http
      .singleRequest(
        request = HttpRequest(
          method = HttpMethods.POST,
          uri = system.settings.config.getString(s"app.notify.${mType}"),
          entity = HttpEntity(
            contentType = MediaTypes.`application/json`,
            string = data.toJson
          )
        )
      )
      .flatMap {
        case HttpResponse(_, _, entity, _) => {
          entity.dataBytes
            .runFold(ByteString.empty)(_ ++ _)
            .map(_.utf8String)
        }
        case ee: HttpResponse => {
          throw new Exception(s"消息发送失败 -> ${ee.toString()}")
        }
      }
      .map(Right.apply)
      .recover {
        case e => Left(e.getMessage)
      }
      .foreach {
        case Left(value)  => logger.error(value)
        case Right(value) => logger.info("消息发送成功 -> " + value)
      }
  }

}
