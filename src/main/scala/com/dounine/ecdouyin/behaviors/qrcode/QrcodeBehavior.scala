package com.dounine.ecdouyin.behaviors.qrcode

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.ecdouyin.behaviors.mechine.MechineBase
import com.dounine.ecdouyin.behaviors.order.OrderBase
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel}
import com.dounine.ecdouyin.model.types.service.AppPage
import com.dounine.ecdouyin.model.types.service.AppPage.AppPage
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.tools.akka.chrome.{ChromePools, ChromeResource}
import com.dounine.ecdouyin.tools.json.JsonParse
import com.dounine.ecdouyin.tools.util.QrcodeUtil
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.{By, OutputType}
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.util.chaining._
import scala.concurrent.Future
import scala.concurrent.duration._

object QrcodeBehavior extends JsonParse {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("QrcodeBehavior")

  sealed trait Command extends BaseSerializer

  final case object Shutdown extends Command

  final case object Ack extends Command

  final case class DataStore(
      qrcode: Option[String],
      order: Option[OrderModel.DbInfo],
      resource: Option[ChromeResource]
  ) extends BaseSerializer

  final case class Create(order: OrderModel.DbInfo)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends BaseSerializer

  final case class CreateOk(
      request: Create,
      qrcode: String
  ) extends BaseSerializer

  final case class CreateFail(request: Create, msg: String)
      extends BaseSerializer

  final case class ChromeAskOk(request: Create, source: ChromeResource)
      extends BaseSerializer

  final case class ChromeAskFail(request: Create, msg: String)
      extends BaseSerializer

  final case class QrcodeOk(
      driver: RemoteWebDriver,
      order: OrderModel.DbInfo,
      qrcode: String
  ) extends BaseSerializer

  final case class QrcodeFail(order: OrderModel.DbInfo, msg: String)
      extends BaseSerializer

  final case class PayOk(
      order: OrderModel.DbInfo
  ) extends BaseSerializer

  final case class PayFail(order: OrderModel.DbInfo, msg: String)
      extends BaseSerializer

  private final val logger = LoggerFactory.getLogger(QrcodeBehavior.getClass)

  def apply(entityId: PersistenceId): Behavior[BaseSerializer] =
    Behaviors.setup { context =>
      {
        val sharding = ClusterSharding(context.system)
        implicit val materializer =
          SystemMaterializer(context.system).materializer

        def stop(data: DataStore): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Create(order) => {
              logger.info(e.logJson)
              Source
                .future(
                  Future {
                    ChromePools(context.system).pools.borrowObject()
                  }(context.executionContext)
                )
                .map(r => ChromeAskOk(e, r))
                .idleTimeout(3.seconds)
                .recover {
                  case ee => ChromeAskFail(e, ee.getMessage)
                }
                .runForeach(result => {
                  context.self.tell(result)
                })
              stop(
                data.copy(
                  order = Option(order)
                )
              )
            }
            case e @ ChromeAskFail(request, msg) => {
              logger.error(e.logJson)
              request.replyTo.tell(CreateFail(request, msg))
              Behaviors.stopped
            }
            case e @ ChromeAskOk(request, source) => {
              logger.info("chromeAskOk")
              val order = request.order
              Source
                .future(
                  source.driver("douyin_cookie")
                )
                .map(driver => {
                  driver.tap(_.findElementByClassName("btn").click())
                })
                .log("请联系管理员进行登录操作")
                .map(driver => {
                  logger.info("输入帐号")
                  driver.tap(
                    _.findElementByTagName("input").sendKeys(order.account)
                  )
                })
                .map(driver => {
                  logger.info("确认帐号")
                  driver.tap(_.findElementByClassName("confirm-btn").click())
                })
                .delay(500.milliseconds)
                .mapAsync(1)(driver => {
                  Future {
                    logger.info("输入自定义金额")
                    driver
                      .findElementByClassName("customer-recharge")
                      .click()
                    driver
                      .findElementByClassName("customer-recharge")
                      .findElement(By.tagName("input"))
                      .sendKeys(order.money.toString)
                    driver.findElementByClassName("pay-button").click()
                    driver
                  }(context.executionContext)
                })
                .mapAsync(1)(driver => {
                  logger.info("查找弹出框")
                  Source(1 to 4)
                    .throttle(1, 500.milliseconds)
                    .map(_ => {
                      try {
                        Option(driver.findElementByClassName("check-content"))
                      } catch {
                        case _: Throwable => Option.empty
                      }
                    })
                    .filter(_.isDefined)
                    .take(1)
                    .delay(500.milliseconds)
                    .orElse(Source.single(1))
                    .map { _ =>
                      {
                        try {
                          driver
                            .findElementByClassName("check-content")
                            .findElement(By.className("right"))
                            .click()
                          logger.info("点击弹出框确认")
                          true
                        } catch {
                          case e =>
                            e.printStackTrace()
                            logger.error("点击弹出框失败")
                            false
                        }
                      }
                    }
                    .runWith(Sink.head)
                    .map(_ => driver)(context.executionContext)
                })
                .mapAsync(1)(driver => {
                  logger.info("检测是否已经跳转到支付页面")
                  Source(1 to 3)
                    .throttle(1, 1.seconds)
                    .map(_ => driver.getCurrentUrl)
                    .filter(_.contains("tp-pay.snssdk.com"))
                    .take(1)
                    .runWith(Sink.head)
                    .recover {
                      case _ => throw new Exception("支付页面无法跳转、请检查")
                    }(context.executionContext)
                    .map(_ => driver)(context.executionContext)
                })
                .map(driver => {
                  logger.info("切换微信支付")
                  driver.tap(
                    _.findElementByClassName("pay-channel-wx")
                      .click()
                  )
                })
                .named("判断是否已经成功跳转到支付页面")
                .mapAsync(1)(driver => {
                  logger.info("获取付款二维码图片")
                  Source
                    .single(order.orderId.toString)
                    .delay(300.milliseconds)
                    .mapAsync(1)(orderId => {
                      Future {
                        (
                          driver
                            .findElementByClassName(
                              "pay-method-scanpay-qrcode-image"
                            )
                            .getScreenshotAs(OutputType.FILE),
                          orderId
                        )
                      }(context.executionContext)
                    })
                    .map({
                      case (file, orderId) => {
                        (orderId, file, driver)
                      }
                    })
                    .runWith(Sink.head)
                })
                .named("获取支付二维码图片")
                .mapAsync(1) { result =>
                  {
                    Future {
                      QrcodeUtil.parse(result._2, "utf-8") match {
                        case Some(value) => (value, result._2, result._3)
                        case None        => throw new Exception("二维码识别失败")
                      }
                    }(context.executionContext)
                  }
                }
                .initialTimeout(10.seconds)
                .log("流异常请联系管理员")
                .map(result => {
                  request.replyTo.tell(
                    CreateOk(
                      request = request,
                      result._2.getAbsolutePath
                    )
                  )
                  QrcodeOk(result._3, order, result._2.getAbsolutePath)
                })
                .recover {
                  case e: Throwable => {
                    request.replyTo.tell(
                      CreateFail(request = request, e.getMessage)
                    )
                    QrcodeFail(order, e.getMessage)
                  }
                }
                .runForeach(result => {
                  context.self.tell(result)
                })

              idle(
                data.copy(
                  resource = Option(source)
                )
              )
            }
          }

        def idle(data: DataStore): Behavior[BaseSerializer] =
          Behaviors.receiveMessage {
            case e @ Shutdown => {
              logger.info(e.logJson)
              Behaviors.stopped
            }
            case e @ QrcodeOk(driver, order, qrcode) => {
              logger.info(e.logJson)
              Source(1 to 60)
                .throttle(1, 1.seconds)
                .map(_ => driver.getCurrentUrl)
                .filter(_.contains("result?app_id"))
                .take(1)
                .map(_ => {
                  OrderBase.WebPaySuccess(order)
                })
                .recover {
                  case e: Throwable => OrderBase.WebPayFail(order, e.getMessage)
                }
                .runForeach(result => {
                  context.self.tell(Shutdown)
                  sharding
                    .entityRefFor(
                      OrderBase.typeKey,
                      OrderBase.typeKey.name
                    )
                    .tell(result)
                })
              idle(
                data.copy(
                  qrcode = Option(qrcode)
                )
              )
            }
            case e @ QrcodeFail(order, msg) => {
              logger.error(e.logJson)
              Behaviors.stopped
            }
          }

        stop(
          DataStore(
            qrcode = None,
            order = None,
            resource = None
          )
        )
      }
    }

}
