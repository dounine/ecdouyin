package com.dounine.ecdouyin.behaviors.client

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.model.RemoteAddress
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.{
  KillSwitches,
  OverflowStrategy,
  SystemMaterializer,
  UniqueKillSwitch
}
import akka.util.Timeout
import com.dounine.ecdouyin.behaviors.mechine.MechineBase
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel}
import com.dounine.ecdouyin.model.types.service.AppPage
import com.dounine.ecdouyin.model.types.service.AppPage.AppPage
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.tools.json.JsonParse
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object SocketBehavior extends JsonParse {

  sealed trait Command extends BaseSerializer

  final case object Shutdown extends Command

  final case object Ack extends Command

  final case class MessageReceive(
      actor: ActorRef[Command],
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
      image: String,
      domain: String,
      orderId: Long,
      money: Int,
      volume: Int,
      platform: PayPlatform,
      timeout: FiniteDuration
  ) extends BaseSerializer

  private final val logger = LoggerFactory.getLogger(SocketBehavior.getClass)

  def apply(mechineId: String): Behavior[BaseSerializer] =
    Behaviors.setup { context =>
      {
        val sharding = ClusterSharding(context.system)
        implicit val materializer =
          SystemMaterializer(context.system).materializer

        def datas(data: DataStore): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Ack => {
              Behaviors.same
            }
            case e @ Connected(actor) => {
              logger.info(e.logJson)
              actor.tell(Ack)
              sharding
                .entityRefFor(
                  MechineBase.typeKey,
                  mechineId
                )
                .tell(
                  MechineBase.SocketConnect(context.self)
                )
              Behaviors.same
            }
            case e @ InitActor(actor) => {
              logger.info(e.logJson)
              datas(
                data.copy(
                  client = Option(actor)
                )
              )
            }
            case e @ OrderCreate(
                  image,
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
                        "image" -> image,
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
              try {
                val messageData = message.jsonTo[Message[Map[String, Any]]]
                val mechineBehavior = sharding
                  .entityRefFor(
                    MechineBase.typeKey,
                    mechineId
                  )
                messageData.`type` match {
                  case "sync" => {
                    val syncInfo = messageData.data.toJson.jsonTo[SyncInfo]
                    syncInfo.page match {
                      case AppPage.jumpedWechatPage => {
                        mechineBehavior.tell(MechineBase.SocketWechatPage())
                      }
                      case AppPage.jumpedScannedPage => {
                        mechineBehavior.tell(MechineBase.SocketScanned())
                      }
                      case AppPage.jumpedQrcodeChoosePage => {
                        mechineBehavior.tell(MechineBase.SocketQrcodeChoose())
                      }
                      case AppPage.jumpedQrcodeIdentifyPage => {
                        mechineBehavior.tell(MechineBase.SocketQrcodeIdentify())
                      }
                      case AppPage.jumpedQrcodeUnIdentifyPage => {
                        mechineBehavior.tell(
                          MechineBase.SocketQrcodeUnIdentify(
                            syncInfo.screen.get
                          )
                        )
                      }
                      case AppPage.jumpedPaySuccessPage => {
                        mechineBehavior.tell(MechineBase.SocketPaySuccess())
                      }
                      case AppPage.jumpedPayFailPage => {
                        mechineBehavior.tell(
                          MechineBase.SocketPayFail(syncInfo.screen.get)
                        )
                      }
                      case AppPage.jumpedTimeoutPage => {
                        mechineBehavior.tell(
                          MechineBase.SocketTimeout(syncInfo.screen)
                        )
                      }

                    }
                  }
                }
              } catch {
                case e =>
              }
              actor.tell(Ack)
              Behaviors.same
            }
            case e @ Shutdown => {
              logger.info(e.logJson)
              sharding
                .entityRefFor(
                  MechineBase.typeKey,
                  mechineId
                )
                .tell(
                  MechineBase.Shutdown()(context.self)
                )
              Behaviors.same
            }
            case e @ MechineBase.ShutdownOk(request, id) => {
              logger.info(e.logJson)
              Behaviors.stopped
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
