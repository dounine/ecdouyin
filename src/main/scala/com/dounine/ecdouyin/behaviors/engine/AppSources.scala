package com.dounine.ecdouyin.behaviors.engine

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.event.LogMarker
import akka.stream.scaladsl.{DelayStrategy, Flow}
import akka.stream.{Attributes, DelayOverflowStrategy}
import com.dounine.ecdouyin.behaviors.engine.socket.AppClient
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.tools.json.{ActorSerializerSuport, JsonParse}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object AppSources extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(AppSources.getClass)
  case class AppInfo(
      appId: String,
      client: ActorRef[BaseSerializer],
      balance: BigDecimal
  ) extends BaseSerializer {
    override def hashCode(): Int = appId.hashCode
    override def equals(obj: Any): Boolean = {
      if (obj == null) {
        false
      } else {
        appId == obj.asInstanceOf[AppInfo].appId
      }
    }
  }

  sealed trait Event extends BaseSerializer

  case class Online(
      appInfo: AppInfo
  ) extends Event

  case class Offline(appId: String) extends Event

  case class Idle(
      appInfo: AppInfo
  ) extends Event

  /**
    * 推送支付消息给手机
    */
  case class PayPush(
      request: QrcodeSources.CreateOrderPush,
      qrcode: String
  ) extends Event

  implicit class FlowLog(data: Flow[BaseSerializer, BaseSerializer, NotUsed])
      extends JsonParse {
    def log(): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
      data
        .logWithMarker(
          s"appMarker",
          (e: BaseSerializer) =>
            LogMarker(
              name = s"appMarker"
            ),
          (e: BaseSerializer) => e.logJson
        )
        .withAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Info
          )
        )

    }
  }

  def createCoreFlow(
      system: ActorSystem[_]
  ): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
    val domain = system.settings.config.getString("app.file.domain")

    Flow[BaseSerializer]
      .collectType[Event]
      .delayWith(
        delayStrategySupplier = () =>
          DelayStrategy.linearIncreasingDelay(
            increaseStep = 1.seconds,
            needsIncrease = {
              case Idle(_) => true
              case _       => false
            },
            maxDelay = 3.seconds
          ),
        DelayOverflowStrategy.backpressure
      )
      .log()
      .statefulMapConcat { () =>
        var online = Set[AppInfo]()
        var offline = Set[String]()

        {
          case PayPush(request, qrcode) => {
            request.request.appInfo.client.tell(
              AppClient.OrderCreate(
                qrcode = qrcode,
                domain = domain,
                orderId = request.order.orderId,
                money = request.order.money,
                volume = request.order.volumn,
                platform = request.order.platform,
                timeout = 30.seconds
              )
            )
            Nil
          }
          case Online(appInfo) => {
            online = online ++ Set(appInfo)
            OrderSources.AppWorkPush(appInfo) :: Nil
          }
          case Offline(appId) => {
            offline = offline ++ Set(appId)
            online = online.filterNot(_.appId == appId)
            Nil
          }
          case Idle(appInfo) => {
            if (offline.contains(appInfo.appId)) {
              offline = offline.filterNot(_ == appInfo.appId)
              Nil
            } else {
              OrderSources.AppWorkPush(
                appInfo
              ) :: Nil
            }
          }
          case ee @ _ => ee :: Nil
        }
      }
      .log()
  }

}
