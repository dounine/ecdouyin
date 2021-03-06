package com.dounine.ecdouyin.behaviors.engine.socket

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.dounine.ecdouyin.behaviors.engine.{AppSources, CoreEngine}
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.model.types.service.AppPage.AppPage
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.tools.json.{ActorSerializerSuport, JsonParse}
import com.dounine.ecdouyin.tools.util.DingDing
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.duration.FiniteDuration

object AppClient extends ActorSerializerSuport {

  sealed trait Command extends BaseSerializer

  final case object Shutdown extends Command

  final case object Ack extends Command

  final case class MessageReceive(
      actor: ActorRef[BaseSerializer],
      message: String
  ) extends Command

  final case class Fail(msg: String) extends Command

  final case class Message[T](
      `type`: String,
      data: T
  ) extends Command

  final case class InitActor(
      actor: ActorRef[BaseSerializer]
  ) extends Command

  final case class Connected(
      client: ActorRef[Command]
  ) extends Command

  final case class OutgoingMessage(
      `type`: String,
      data: Option[Any] = Option.empty,
      msg: Option[String] = Option.empty
  ) extends Command

  final case class SyncInfo(
      page: AppPage,
      screen: Option[String]
  ) extends Command

  final case class DataStore(
      client: Option[ActorRef[BaseSerializer]]
  ) extends BaseSerializer

  final case class OrderCreate(
      qrcode: String,
      domain: String,
      orderId: Long,
      money: Int,
      volume: Int,
      platform: PayPlatform,
      timeout: FiniteDuration
  ) extends Command

  private final val logger = LoggerFactory.getLogger(AppClient.getClass)

  def apply(appId: String): Behavior[BaseSerializer] =
    Behaviors.setup[BaseSerializer] { context: ActorContext[BaseSerializer] =>
      {
        val sharding = ClusterSharding(context.system)
        val startTime = LocalDateTime.now()
        def datas(data: DataStore): Behavior[BaseSerializer] =
          Behaviors
            .receiveMessage[BaseSerializer] {
              case e @ Ack => {
                Behaviors.same
              }
              case e @ Connected(actor) => {
                logger.info(e.logJson)
                actor.tell(Ack)
                Behaviors.same
              }
              case e @ CoreEngine.MessageOk(request) => {
                logger.info(e.logJson)
                Behaviors.same
              }
              case e @ CoreEngine.MessageFail(request, message) => {
                logger.info(e.logJson)
                Behaviors.same
              }
              case e @ InitActor(actor) => {
                logger.info(e.logJson)
                sharding
                  .entityRefFor(
                    CoreEngine.typeKey,
                    CoreEngine.typeKey.name
                  )
                  .tell(
                    CoreEngine.Message(
                      AppSources.Online(
                        AppSources.AppInfo(
                          appId = appId,
                          client = context.self,
                          balance = BigDecimal("0.00")
                        )
                      )
                    )(context.self)
                  )
                DingDing.sendMessage(
                  DingDing.MessageType.app,
                  data = DingDing.MessageData(
                    markdown = DingDing.Markdown(
                      title = "????????????",
                      text = s"""
                                |# ?????????
                                | - appId: ${appId}
                                | - startTime: ${startTime}
                                | - time: ${LocalDateTime.now()}
                                |""".stripMargin
                    )
                  ),
                  context.system
                )
                datas(
                  data.copy(
                    client = Option(actor)
                  )
                )
              }
              case e @ OrderCreate(
                    qrcode,
                    domain,
                    orderId,
                    money,
                    volume,
                    platform,
                    timeout
                  ) => {
                logger.info(e.logJson)
                data.client.foreach(
                  _.tell(
                    OutgoingMessage(
                      `type` = "order",
                      data = Option(
                        Map(
                          "qrcode" -> qrcode,
                          "domain" -> domain,
                          "orderId" -> orderId,
                          "money" -> money,
                          "volume" -> volume,
                          "platform" -> platform,
                          "timeout" -> timeout
                        )
                      )
                    )
                  )
                )
                Behaviors.same
              }
              case e @ MessageReceive(actor, message) => {
                logger.info(e.logJson)
                actor.tell(Ack)
                Behaviors.same
              }
              case e @ Shutdown => {
                logger.info(e.logJson)
                DingDing.sendMessage(
                  DingDing.MessageType.app,
                  data = DingDing.MessageData(
                    markdown = DingDing.Markdown(
                      title = "????????????",
                      text = s"""
                                |# ????????????
                                | - appId: ${appId}
                                | - startTime: ${startTime}
                                | - time: ${LocalDateTime.now()}
                                |""".stripMargin
                    )
                  ),
                  context.system
                )
                sharding
                  .entityRefFor(
                    CoreEngine.typeKey,
                    CoreEngine.typeKey.name
                  )
                  .tell(
                    CoreEngine.Message(
                      AppSources.Offline(
                        appId = appId
                      )
                    )(context.self)
                  )
                Behaviors.same
              }
            }

        datas(
          DataStore(
            client = Option.empty
          )
        )
      }
    }

}
