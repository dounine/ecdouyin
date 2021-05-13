package com.dounine.ecdouyin.behaviors.qrcode

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.typed.scaladsl.ActorSink
import com.dounine.ecdouyin.behaviors.mechine.MechineBase
import com.dounine.ecdouyin.behaviors.order.OrderBase
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel}
import com.dounine.ecdouyin.model.types.service.AppPage
import com.dounine.ecdouyin.model.types.service.AppPage.AppPage
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.tools.akka.chrome.{ChromePools, Chrome}
import com.dounine.ecdouyin.tools.json.JsonParse
import com.dounine.ecdouyin.tools.util.QrcodeUtil
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.{By, OutputType}
import org.slf4j.LoggerFactory

import java.io.File
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
      resource: Option[Chrome]
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

  final case class ChromeAskOk(request: Create) extends BaseSerializer

  final case class ChromeAskFail(request: Create, msg: String)
      extends BaseSerializer

  final case class QrcodeOk(
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

        implicit val ec = context.executionContext

        var chromeResource: Option[Chrome] = None
        Behaviors
          .receiveMessage[BaseSerializer] {
            case e @ Create(_) => {
              logger.info(e.logJson)
              Source
                .future(
                  Future {
                    ChromePools(context.system).pools.borrowObject()
                  }(context.executionContext)
                )
                .map(r => {
                  chromeResource = Option(r)
                  ChromeAskOk(e)
                })
                .idleTimeout(5.seconds)
                .recover {
                  case _ => ChromeAskFail(e, "chrome 资源申请失败")
                }
                .runForeach(result => {
                  context.self.tell(result)
                })
              Behaviors.same
            }
            case e @ ChromeAskFail(request, msg) => {
              logger.error(e.logJson)
              request.replyTo.tell(CreateFail(request, msg))
              Behaviors.stopped
            }
            case e @ ChromeAskOk(request) => {
              logger.info("chromeAskOk")
              val order = request.order
              Source
                .future(
                  chromeResource.get.driver("douyin_cookie")
                )
                .mapAsync(1)(driver => {
                  Future {
                    try {
                      driver.tap(_.findElementByClassName("btn").click())
                    } catch {
                      case e => throw new Exception("请联系管理员进行登录操作")
                    }
                  }
                })
                .named("查找帐号点击切换")
                .mapAsync(1)(driver => {
                  Future {
                    logger.info("输入帐号")
                    driver.tap(
                      _.findElementByTagName("input").sendKeys(order.account)
                    )
                  }
                })
                .mapAsync(1)(driver => {
                  Future{
                    logger.info("确认帐号")
                    driver.tap(_.findElementByClassName("confirm-btn").click())
                  }
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
                  }
                })
                .mapAsync(1)(driver => {
                  logger.info("查找弹出框")
                  Source(1 to 4)
                    .initialDelay(500.milliseconds)
                    .mapAsync(1)(_ => {
                      Future{
                        try {
                          logger.info("begin开始查询弹出窗")
                          driver.findElementByClassName("check-content")
                          logger.info("end开始查询弹出窗")
                          Option("已弹框")
                        } catch {
                          case _: Throwable => {
                            logger.info("error开始查询弹出窗")
                            if (
                              driver.getCurrentUrl.contains("tp-pay.snssdk.com")
                            ) {
                              logger.info("已跳转")
                              Option("已跳转")
                            } else Option.empty[String]
                          }
                        }
                      }
                    })
                    .filter(_.isDefined)
                    .take(1)
                    .orElse(Source.single(Option("没弹框")))
                    .mapAsync(1)({
                      msg =>
                        {
                          Future{
                            if (msg.get == "已弹框") {
                              try {
                                driver
                                  .findElementByClassName("check-content")
                                  .findElement(By.className("right"))
                                  .click()
                                logger.info("点击弹出框确认")
                                "已点弹框"
                              } catch {
                                case e =>
                                  e.printStackTrace()
                                  logger.error("点击弹出框失败")
                                  throw new Exception("点击确认弹框失败")
                              }
                            } else if (msg.get == "已跳转") {
                              "已跳转"
                            } else "没弹框"
                          }
                        }
                    })
                    .runWith(Sink.head)
                    .map(i => (i, driver))
                })
                .mapAsync(1)(tp2 => {
                  val driver = tp2._2
                  val msg = tp2._1
                  logger.info(msg)
                  if (msg == "已点弹框" || msg == "没弹框") {
                    logger.info("检测是否已经跳转到支付页面")
                    Source(1 to 5)
                      .throttle(1, 500.milliseconds)
                      .map(_ => driver.getCurrentUrl)
                      .filter(_.contains("tp-pay.snssdk.com"))
                      .take(1)
                      .runWith(Sink.head)
                      .recover {
                        case _ => throw new Exception("支付页面无法跳转、请检查")
                      }(context.executionContext)
                      .map(_ => driver)
                  } else {
                    Future.successful(driver)
                  }
                })
                .mapAsync(1)(driver => {
                  Future{
                    logger.info("切换微信支付")
                    driver.tap(
                      _.findElementByClassName("pay-channel-wx")
                        .click()
                    )
                  }
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
                          orderId
,
                            driver
                            .findElementByClassName(
                              "pay-method-scanpay-qrcode-image"
                            )
                            .getScreenshotAs(OutputType.FILE).getAbsolutePath
                        )
                      }
                    })
                    .runWith(Sink.head)
                })
                .named("获取支付二维码图片")
                .mapAsync(1) { result =>
                  {
                    Future {
                      QrcodeUtil.parse(new File(result._2), "utf-8") match {
                        case Some(value) => {
                          logger.info("解析二维码："+value)
                          (value, result._2)
                        }
                        case None        => throw new Exception("二维码识别失败")
                      }
                    }
                  }
                }
                .initialTimeout(60.seconds)
                .log("流异常请联系管理员")
                .map(result => {
                  request.replyTo.tell(
                    CreateOk(
                      request = request,
                      result._2
                    )
                  )
                  QrcodeOk(order, result._2)
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
              Behaviors.same
            }

            case e @ Shutdown => {
              logger.info(e.logJson)
              Behaviors.stopped
            }
            case e@OrderBase.WebPaySuccess(order) => {
              logger.info(e.logJson)
              sharding
                .entityRefFor(
                  OrderBase.typeKey,
                  OrderBase.typeKey.name
                )
                .tell(e)
              Behaviors.stopped
            }
            case e@OrderBase.WebPayFail(order,msg) => {
              logger.info(e.logJson)
              sharding
                .entityRefFor(
                  OrderBase.typeKey,
                  OrderBase.typeKey.name
                )
                .tell(e)
              Behaviors.stopped
            }
            case e @ QrcodeOk(order, qrcode) => {
              logger.info(e.logJson)
              Source(1 to 60)
                .throttle(1, 1.seconds)
                .map(_ => {
                  chromeResource.get.webDriver.getCurrentUrl
                })
                .filter(_.contains("result?app_id"))
                .map(_ => {
                  OrderBase.WebPaySuccess(order)
                })
                .take(1)
                .orElse(Source.single(OrderBase.WebPayFail(order, "not pay")))
                .recover {
                  case e: Throwable => {
                    OrderBase.WebPayFail(order, e.getMessage)
                  }
                }
                .runForeach(result => {
                  sharding
                    .entityRefFor(
                      OrderBase.typeKey,
                      OrderBase.typeKey.name
                    )
                    .tell(result)
                  context.self.tell(Shutdown)
                })
              Behaviors.same
            }
            case e @ QrcodeFail(order, msg) => {
              logger.error(e.logJson)
              Behaviors.stopped
            }
          }
          .receiveSignal {
            case (_, PostStop) => {
              logger.info(s"qrcode ${entityId.id} stop")
              chromeResource.foreach(i =>
                ChromePools(context.system).pools.returnObject(i)
              )
              Behaviors.stopped
            }
          }
      }
    }

}
