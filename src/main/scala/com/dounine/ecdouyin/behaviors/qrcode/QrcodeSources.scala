package com.dounine.ecdouyin.behaviors.qrcode

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.Source
import com.dounine.ecdouyin.model.models.OrderModel
import com.dounine.ecdouyin.tools.akka.chrome.{ChromePools, Chrome}
import org.openqa.selenium.{By, OutputType}
import org.slf4j.LoggerFactory

import java.io.File
import scala.concurrent.Future
import scala.util.{Failure, Success}

object QrcodeSources {

  private val logger = LoggerFactory.getLogger(QrcodeSources.getClass)

  /**
    * 申请chrome浏览器
    * @param system actor
    * @return Source[Either[Throwable,ChromeResource]]
    */
  private def createChromeSource(
      system: ActorSystem[_]
  ): Source[Either[Throwable, Chrome], NotUsed] = {
    implicit val ec = system.executionContext
    import scala.concurrent.duration._
    Source
      .future {
        Future {
          ChromePools(system).pools.borrowObject()
        }
      }
      .idleTimeout(10.seconds)
      .map(Right.apply)
      .recover {
        case e: Throwable => {
          e.printStackTrace()
          Left(new Exception("chrome申请失败"))
        }
      }
  }

  /**
    * 获取支付二维码
    * @param system Actor system
    * @param order 定单
    * @return Left[Throwable] Right[source,order,qrcode]
    */
  def createQrcodeSource(
      system: ActorSystem[_],
      order: OrderModel.DbInfo
  ): Source[Either[Throwable, (Chrome, OrderModel.DbInfo, String)], NotUsed] = {
    implicit val ec = system.executionContext
    import scala.concurrent.duration._
    import scala.util.chaining._
    createChromeSource(system)
      .map {
        case Left(value)  => throw value
        case Right(value) => value
      }
      .flatMapConcat { source =>
        {
          Source
            .future(source.driver("douyin_cookie"))
            .mapAsync(1) { driver =>
              Future {
                logger.info("切换用户")
                driver.tap(_.findElementByClassName("btn").click())
              }.recover {
                case _ => throw new Exception("无法点击切换用户按钮")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("输入帐号")
                driver.tap(
                  _.findElementByTagName("input").sendKeys(order.account)
                )
              }.recover {
                case _ => throw new Exception("无法输入帐号")
              }
            }
            .mapAsync(1)(driver => {
              Future {
                logger.info("确认帐号")
                driver.tap(_.findElementByClassName("confirm-btn").click())
              }.recover {
                case _ => throw new Exception("无法点击确认帐号")
              }
            })
            .mapAsync(1) { driver =>
              Future {
                logger.info("点击自定义充值金额按钮")
                driver.tap(
                  _.findElementByClassName("customer-recharge").click()
                )
              }.recover {
                case _ => throw new Exception("无法点击自定义充值按钮")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("输入充值金额")
                driver.tap(
                  _.findElementByClassName("customer-recharge")
                    .findElement(By.tagName("input"))
                    .sendKeys(order.money.toString)
                )
              }.recover {
                case _ => throw new Exception("无法输入充值金额")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("点击支付")
                driver.tap(_.findElementByClassName("pay-button").click())
              }.recover {
                case _ => throw new Exception("无法点击支付按钮")
              }
            }
            .flatMapConcat { driver =>
              Source(1 to 2)
                .throttle(1, 200.milliseconds)
                .mapAsync(1) { _ =>
                  Future {
                    logger.info("查询二次确认框跟跳转")
                    if (driver.getCurrentUrl.contains("tp-pay.snssdk.com")) {
                      logger.info("已跳转")
                      Right(Right("已跳转"))
                    } else {
                      Right(
                        Left(
                          driver
                            .findElementByClassName("check-content")
                            .findElement(By.className("right"))
                            .click()
                        )
                      )
                    }
                  }.recover {
                    case _ => {
                      Left(new Exception("没有二次确认框也没跳转"))
                    }
                  }
                }
                .filter(_.isRight)
                .take(1)
                .orElse(Source.single(Left(new Exception("没有二次确认框也没跳转"))))
                .flatMapConcat {
                  case Left(error) => throw error
                  case Right(value) =>
                    value match {
                      case Left(clickSuccess) => {
                        logger.info("检查页面是否跳转")
                        Source(1 to 4)
                          .throttle(1, 500.milliseconds)
                          .map(_ => driver.getCurrentUrl)
                          .filter(_.contains("tp-pay.snssdk.com"))
                          .map(_ => Right(true))
                          .take(1)
                          .orElse(
                            Source.single(Left(new Exception("支付支付页面无法跳转")))
                          )
                      }
                      case Right(jumpPayPage) => Source.single(Right(true))
                    }
                }
                .mapAsync(1) {
                  case Left(error) => throw error
                  case Right(value) =>
                    Future {
                      logger.info("切换微信支付")
                      driver.tap(
                        _.findElementByClassName("pay-channel-wx")
                          .click()
                      )
                    }.recover {
                      case _ => throw new Exception("切换微信支付失败")
                    }
                }
                .flatMapConcat { driver =>
                  Source(1 to 3)
                    .mapAsync(1) { _ =>
                      Future {
                        logger.info("查找二维码图片")
                        Right(
                          driver
                            .findElementByClassName(
                              "pay-method-scanpay-qrcode-image"
                            )
                        )
                      }.recover {
                        case _ => Left(new Exception("支付二维找不到"))
                      }
                    }
                    .filter(_.isRight)
                    .take(1)
                    .orElse(Source.single(Left(new Exception("支付二维找不到"))))
                    .mapAsync(1) {
                      case Left(error) => throw error
                      case Right(_) =>
                        Future {
                          logger.info("二维码图片保存")
                          driver
                            .findElementByClassName(
                              "pay-method-scanpay-qrcode-image"
                            )
                            .getScreenshotAs(OutputType.FILE)
                        }.recover {
                          case e => {
                            logger.error(e.getMessage)
                            throw new Exception("二维码保存失败")
                          }
                        }
                    }
                }
            }
            .map(file => Right((source, order, file.getAbsolutePath)))
            .recover {
              case e: Throwable => {
                e.printStackTrace()
                ChromePools(system).pools.returnObject(source)
                Left(e)
              }
            }
        }
      }
  }

  /**
    * 监听用户是否支付
    * @param system Actor system
    * @param source chrome source
    * @param order order
    * @return Left[Throwable] Right[OrderModel.DbInfo]
    */
  def createListenPay(
      system: ActorSystem[_],
      chrome: Chrome,
      order: OrderModel.DbInfo
  ): Source[Either[Throwable, OrderModel.DbInfo], NotUsed] = {
    implicit val ec = system.executionContext
    import scala.concurrent.duration._
    Source(1 to 60)
      .throttle(1, 1.seconds)
      .map(_ => {
        chrome.driver().getCurrentUrl
      })
      .filter(_.contains("result?app_id"))
      .map(_ => Right(order))
      .take(1)
      .orElse(Source.single(Left(new Exception("未支付"))))
      .recover {
        case e => {
          e.printStackTrace()
          Left(new Exception("未支付"))
        }
      }
      .watchTermination()((pv, future) => {
        future.foreach(_ => {
          ChromePools(system).pools.returnObject(chrome)
        })
        pv
      })
  }

}
