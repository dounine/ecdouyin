package com.dounine.ecdouyin.router.routers

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{CompletionStrategy, _}
import akka.{NotUsed, actor}
import com.dounine.ecdouyin.behaviors.engine.socket.AppClient
import com.dounine.ecdouyin.model.models.BaseSerializer
import org.json4s.native.Serialization.write
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

class WebsocketRouter(system: ActorSystem[_]) extends SuportRouter {

  private final val logger: Logger =
    LoggerFactory.getLogger(classOf[WebsocketRouter])
  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val actorSystem: actor.ActorSystem = materializer.system
  implicit val executionContext: ExecutionContextExecutor =
    materializer.executionContext

  val route: Route = concat(
    get {
      path(pm = "mechine" / Segment) { mechineId =>
        {
          extractClientIP { ip =>
            {
              logger.info(s"socket connect {}", ip)
              handleWebSocketMessages(createConnect(mechineId))
            }
          }
        }
      }
    }
  )

  private var clientId = 0

  private def createConnect(mechineId: String): Flow[Message, Message, _] = {

    val appClient: ActorRef[BaseSerializer] =
      system.systemActorOf(
        AppClient(mechineId),
        UUID.randomUUID().toString.replaceAll("-", "")
      )

    val completion: PartialFunction[Any, CompletionStrategy] = {
      case AppClient.Shutdown =>
        CompletionStrategy.immediately
    }

    val source: Source[TextMessage.Strict, Unit] = ActorSource
      .actorRefWithBackpressure(
        ackTo = appClient,
        ackMessage = AppClient.Ack,
        completionMatcher = completion,
        failureMatcher = PartialFunction.empty
      )
      .mapMaterializedValue((a: ActorRef[BaseSerializer]) => {
        appClient.tell(AppClient.InitActor(a))
      })
      .collect {
        case e @ AppClient.OutgoingMessage(_, _, _) =>
          TextMessage.Strict(write(e))
      }
      .keepAlive(
        maxIdle = 10.seconds,
        () =>
          TextMessage(
            s"""{"type":"ping","time":${System.currentTimeMillis()}}"""
          )
      )

    val sink: Sink[String, NotUsed] = ActorSink.actorRefWithBackpressure(
      ref = appClient,
      onInitMessage = (responseActorRef: ActorRef[AppClient.Command]) =>
        AppClient
          .Connected(client = responseActorRef),
      messageAdapter =
        (responseActorRef: ActorRef[AppClient.Command], element: String) =>
          AppClient.MessageReceive(
            actor = responseActorRef,
            message = element
          ),
      ackMessage = AppClient.Ack,
      onCompleteMessage = AppClient.Shutdown,
      onFailureMessage = exception => AppClient.Fail(msg = exception.getMessage)
    )

    val incomingMessages =
      Flow[Message]
        .collect({
          case TextMessage.Strict(text)     => Future.successful(text)
          case TextMessage.Streamed(stream) => stream.runFold(zero = "")(_ + _)
          case _                            => Future.failed(new Exception(s"错误消息类型"))
        })
        .mapAsync(1)(identity)
        .to(sink)

    Flow
      .fromSinkAndSourceCoupled(
        sink = incomingMessages,
        source = source
      )
  }

}
