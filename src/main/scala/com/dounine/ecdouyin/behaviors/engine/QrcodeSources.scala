package com.dounine.ecdouyin.behaviors.engine

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.event.LogMarker
import akka.stream.scaladsl.{DelayStrategy, Flow, Source}
import akka.stream.{Attributes, DelayOverflowStrategy}
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel}
import com.dounine.ecdouyin.tools.akka.chrome.{Chrome, ChromePools}
import com.dounine.ecdouyin.tools.json.{ActorSerializerSuport, JsonParse}
import org.openqa.selenium.{By, OutputType}
import org.slf4j.LoggerFactory

import scala.concurrent.Future

object QrcodeSources extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(QrcodeSources.getClass)
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

  case class CreateOrderPush(
      request: OrderSources.AppWorkPush,
      order: OrderModel.DbInfo
  ) extends Event

  implicit class FlowLog(data: Flow[BaseSerializer, BaseSerializer, NotUsed])
      extends JsonParse {
    def log(): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
      data
        .logWithMarker(
          s"qrcodeMarker",
          (e: BaseSerializer) =>
            LogMarker(
              name = s"qrcodeMarker"
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
    implicit val ec = system.executionContext
    Flow[BaseSerializer]
      .collectType[Event]
      .log()
      .flatMapMerge(
        30,
        {
          case r @ CreateOrderPush(request, order) => {
            createQrcodeSource(
              system,
              order
            ).flatMapMerge(
              20,
              {
                case Left(error) => {
                  Source(
                    OrderSources.PayError(r, error.getMessage) :: AppSources
                      .Idle(request.appInfo) :: Nil
                  )
                }
                case Right((chrome, order, qrcode)) =>
                  Source
                    .single(
                      AppSources.PayPush(
                        r,
                        qrcode
                      )
                    )
                    .merge(
                      createListenPay(
                        system,
                        chrome,
                        order
                      ).flatMapMerge(
                        10,
                        {
                          case Left(error) =>
                            Source(
                              OrderSources.PayError(
                                request = r,
                                error = error.getMessage
                              ) :: AppSources.Idle(request.appInfo)
                                :: Nil
                            )
                          case Right(value) =>
                            Source(
                              AppSources.Idle(request.appInfo) :: OrderSources
                                .PaySuccess(
                                  request = r
                                ) :: Nil
                            )
                        }
                      )
                    )
              }
            )
          }
          case ee => Source.single(ee)
        }
      )
      .log()
  }

  /**
    * ??????chrome?????????
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
          Left(new Exception("chrome????????????"))
        }
      }
  }

  /**
    * ?????????????????????
    * @param system Actor system
    * @param order ??????
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
        case Left(value) => {
          value.printStackTrace()
          throw value
        }
        case Right(value) => value
      }
      .flatMapConcat { source =>
        {
          Source
            .future(source.driver("douyin_cookie"))
            .mapAsync(1) { driver =>
              Future {
                logger.info("????????????")
                driver.tap(_.findElementByClassName("btn").click())
              }.recover {
                case _ => throw new Exception("??????????????????????????????")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("????????????")
                driver.tap(
                  _.findElementByTagName("input").sendKeys(order.account)
                )
              }.recover {
                case _ => throw new Exception("??????????????????")
              }
            }
            .mapAsync(1)(driver => {
              Future {
                logger.info("????????????")
                driver.tap(_.findElementByClassName("confirm-btn").click())
              }.recover {
                case _ => throw new Exception("????????????????????????")
              }
            })
            .mapAsync(1) { driver =>
              Future {
                logger.info("?????????????????????????????????")
                driver.tap(
                  _.findElementByClassName("customer-recharge").click()
                )
              }.recover {
                case _ => throw new Exception("?????????????????????????????????")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("??????????????????")
                driver.tap(
                  _.findElementByClassName("customer-recharge")
                    .findElement(By.tagName("input"))
                    .sendKeys(order.money.toString)
                )
              }.recover {
                case _ => throw new Exception("????????????????????????")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("????????????")
                driver.tap(_.findElementByClassName("pay-button").click())
              }.recover {
                case _ => throw new Exception("????????????????????????")
              }
            }
            .flatMapConcat { driver =>
              Source(1 to 4)
                .delayWith(
                  delayStrategySupplier = () =>
                    DelayStrategy.linearIncreasingDelay(
                      increaseStep = 200.milliseconds,
                      needsIncrease = _ => true
                    ),
                  overFlowStrategy = DelayOverflowStrategy.backpressure
                )
                .mapAsync(1) { _ =>
                  Future {
                    logger.info("??????????????????????????????")
                    if (driver.getCurrentUrl.contains("tp-pay.snssdk.com")) {
                      logger.info("?????????")
                      Right(Right("?????????"))
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
                      Left(new Exception("?????????????????????????????????"))
                    }
                  }
                }
                .filter(_.isRight)
                .take(1)
                .orElse(Source.single(Left(new Exception("?????????????????????????????????"))))
                .flatMapConcat {
                  case Left(error) => throw error
                  case Right(value) =>
                    value match {
                      case Left(clickSuccess) => {
                        logger.info("????????????????????????")
                        Source(1 to 4)
                          .delayWith(
                            delayStrategySupplier = () =>
                              DelayStrategy.linearIncreasingDelay(
                                increaseStep = 200.milliseconds,
                                needsIncrease = _ => true
                              ),
                            overFlowStrategy =
                              DelayOverflowStrategy.backpressure
                          )
                          .map(_ => driver.getCurrentUrl)
                          .filter(_.contains("tp-pay.snssdk.com"))
                          .map(_ => Right(true))
                          .take(1)
                          .orElse(
                            Source.single(Left(new Exception("??????????????????????????????")))
                          )
                      }
                      case Right(jumpPayPage) => Source.single(Right(true))
                    }
                }
                .mapAsync(1) {
                  case Left(error) => throw error
                  case Right(value) =>
                    Future {
                      logger.info("??????????????????")
                      driver.tap(
                        _.findElementByClassName("pay-channel-wx")
                          .click()
                      )
                    }.recover {
                      case _ => throw new Exception("????????????????????????")
                    }
                }
                .flatMapConcat { driver =>
                  Source(1 to 3)
                    .delayWith(
                      delayStrategySupplier = () =>
                        DelayStrategy.linearIncreasingDelay(
                          increaseStep = 200.milliseconds,
                          needsIncrease = _ => true
                        ),
                      overFlowStrategy = DelayOverflowStrategy.backpressure
                    )
                    .mapAsync(1) { _ =>
                      Future {
                        logger.info("?????????????????????")
                        Right(
                          driver
                            .findElementByClassName(
                              "pay-method-scanpay-qrcode-image"
                            )
                        )
                      }.recover {
                        case _ => Left(new Exception("?????????????????????"))
                      }
                    }
                    .filter(_.isRight)
                    .take(1)
                    .orElse(Source.single(Left(new Exception("?????????????????????"))))
                    .mapAsync(1) {
                      case Left(error) => throw error
                      case Right(_) =>
                        Future {
                          logger.info("?????????????????????")
                          driver
                            .findElementByClassName(
                              "pay-method-scanpay-qrcode-image"
                            )
                            .getScreenshotAs(OutputType.FILE)
                        }.recover {
                          case e => {
                            logger.error(e.getMessage)
                            throw new Exception("?????????????????????")
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
      .recover {
        case e: Throwable => Left(e)
      }
  }

  /**
    * ????????????????????????
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
      .map(item => {
        chrome.driver().getCurrentUrl
      })
      .filter(_.contains("result?app_id"))
      .map(_ => Right(order))
      .take(1)
      .orElse(Source.single(Left(new Exception("?????????"))))
      .recover {
        case e => {
          e.printStackTrace()
          Left(new Exception("?????????"))
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
