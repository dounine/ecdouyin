package com.dounine.ecdouyin.behaviors.engine

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.{Attributes, DelayOverflowStrategy}
import akka.stream.scaladsl.{DelayStrategy, Flow, Source}
import com.dounine.ecdouyin.behaviors.engine.socket.AppClient
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.tools.json.JsonParse
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.duration._

object AppSources extends JsonParse {

  private val logger = LoggerFactory.getLogger(AppSources.getClass)
  case class AppInfo(
      appId: String,
      client: ActorRef[AppClient.Command],
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

  case class Online(
      appInfo: AppInfo
  ) extends BaseSerializer

  case class Offline(appId: String) extends BaseSerializer

  case class Idle(
      appInfo: AppInfo
  ) extends BaseSerializer

  /**
    * 推送支付消息给手机
    */
  case class PayPush(
      request: OrderSources.CreateOrderPush,
      qrcode: String
  ) extends BaseSerializer

  case class AppWorkPush(
      appInfo: AppSources.AppInfo
  ) extends BaseSerializer

  def createCoreFlow(
      system: ActorSystem[_]
  ): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
    val domain = system.settings.config.getString("app.file.domain")
    Flow[BaseSerializer]
      .delayWith(
        delayStrategySupplier = () =>
          DelayStrategy.linearIncreasingDelay(
            increaseStep = 500.seconds,
            needsIncrease = {
              case Idle(_) => true
              case _       => false
            }
          ),
        DelayOverflowStrategy.backpressure
      )
      .statefulMapConcat { () =>
        var apps = Set[AppInfo]()

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
            apps = apps ++ Set(appInfo)
            AppWorkPush(appInfo) :: Nil
          }
          case Offline(appId) => {
            apps = apps.filterNot(_.appId == appId)
            Nil
          }
          case Idle(appInfo) => {
            apps = apps ++ Set(appInfo)
            AppWorkPush(
              apps.maxBy(_.balance)
            ) :: Nil
          }
          case ee @ _ => {
            ee :: Nil
          }
        }
      }
  }

}
