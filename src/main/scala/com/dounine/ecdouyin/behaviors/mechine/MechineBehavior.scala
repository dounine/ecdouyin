package com.dounine.ecdouyin.behaviors.mechine

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.event.LogMarker
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  RetentionCriteria
}
import akka.stream.{Attributes, Materializer, SystemMaterializer}
import akka.stream.scaladsl.Source
import com.dounine.ecdouyin.behaviors.client.SocketBehavior
import com.dounine.ecdouyin.behaviors.mechine.MechineBase._
import com.dounine.ecdouyin.behaviors.mechine.status.ConnectedStatus.logger
import com.dounine.ecdouyin.behaviors.mechine.status.PaySuccessStatus.logger
import com.dounine.ecdouyin.behaviors.order.OrderBase
import com.dounine.ecdouyin.behaviors.order.OrderBase.WebPayFail
import com.dounine.ecdouyin.behaviors.qrcode.{QrcodeBehavior, QrcodeSources}
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.model.types.service.MechineStatus.MechineStatus
import com.dounine.ecdouyin.tools.json.JsonParse
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object MechineBehavior extends JsonParse {
  private val logger = LoggerFactory.getLogger(MechineBehavior.getClass)

  def apply(
      entityId: PersistenceId
  ): Behavior[BaseSerializer] =
    Behaviors.setup { context: ActorContext[BaseSerializer] =>
      Behaviors.withTimers((timers: TimerScheduler[BaseSerializer]) => {
        val sharding = ClusterSharding(context.system)
        val mechineId = entityId.id.split("\\|").last
        val domain = context.system.settings.config.getString("app.file.domain")
        val statusList = Seq(
          status.DisconnectedStatus(context, timers),
          status.ConnectedStatus(context, timers),
          status.WechatPageStatus(context, timers),
          status.ScannedStatus(context, timers),
          status.QrcodeChooseStatus(context, timers),
          status.QrcodeIdentifyStatus(context, timers),
          status.QrcodeUnIdentifyStatus(context, timers),
          status.PaySuccessStatus(context, timers),
          status.PayFailStatus(context, timers),
          status.TimeoutStatus(context, timers)
        )

        def getStatus(state: State): MechineStatus = {
          statusList.find(_._3 == state.getClass).get._4
        }

        val commandDefaultHandler: (
            State,
            BaseSerializer
        ) => Effect[BaseSerializer, State] = (state, command) =>
          command match {
            case e @ CreateOrder(order, mechineId) => {
              logger.info(command.logJson)
              state.data.order match {
                case Some(order) =>
                  Effect.none.thenRun((latest: State) => {
                    e.replyTo.tell(CreateOrderFail(e, "order exit"))
                  })
                case None => {
                  Effect
                    .persist(command)
                    .thenRun((latest: State) => {

                      /**
                        * 系统流、不要绑定到当前Actor上、因为还有是否付费监控、一分钟后会自动关闭
                        */
                      QrcodeSources
                        .createQrcodeSource(
                          context.system,
                          order
                        )
                        .flatMapConcat {
                          case Left(error) =>
                            Source.single(CreateOrderFail(e, error.getMessage))
                          case Right((chrome,order,qrcode)) =>
                            Source
                              .single(CreateOrderOk(e, qrcode))
                              .concat(
                                QrcodeSources
                                  .createListenPay(
                                    context.system,
                                    chrome,
                                    order
                                  )
                                  .map {
                                    case Left(error) =>
                                      OrderBase
                                        .WebPayFail(
                                          order = order,
                                          msg = error.getMessage
                                        )
                                    case Right(value) =>
                                      OrderBase.WebPaySuccess(value)
                                  }
                              )
                        }
                        .logWithMarker(
                          name = "qrcodeStream",
                          element =>
                            LogMarker(
                              name = "qrcodeMark",
                              properties = Map(
                                "element" -> element
                              )
                            )
                        )
                        .withAttributes(
                          Attributes.logLevels(
                            onElement = Attributes.LogLevels.Info
                          )
                        )
                        .runForeach({
                          case ee @ CreateOrderFail(request, msg) => {
                            sharding
                              .entityRefFor(
                                OrderBase.typeKey,
                                OrderBase.typeKey.name
                              )
                              .tell(
                                ee
                              )
                          }
                          case ee @ CreateOrderOk(request, qrcode) => {
                            timers.startSingleTimer(
                              timeoutName,
                              SocketTimeout(Option.empty),
                              state.data.mechineTimeout
                            )
                            sharding
                              .entityRefFor(
                                OrderBase.typeKey,
                                OrderBase.typeKey.name
                              )
                              .tell(
                                ee
                              )
                            latest.data.actor.foreach(
                              _.tell(
                                SocketBehavior.OrderCreate(
                                  image = qrcode,
                                  domain = domain,
                                  orderId = order.orderId,
                                  money = order.money,
                                  volume = order.volumn,
                                  platform = order.platform,
                                  timeout = state.data.appTimeout
                                )
                              )
                            )
                          }
                          case ee @ OrderBase.WebPaySuccess(order) => {
                            sharding
                              .entityRefFor(
                                OrderBase.typeKey,
                                OrderBase.typeKey.name
                              )
                              .tell(
                                ee
                              )
                          }
                          case ee @ OrderBase.WebPayFail(order, msg) => {
                            sharding
                              .entityRefFor(
                                OrderBase.typeKey,
                                OrderBase.typeKey.name
                              )
                              .tell(
                                ee
                              )
                          }
                        })(Materializer(context.system))
                      e.replyTo.tell(
                        Disable(latest.data.mechineId)
                      )
                    })
                }
              }
            }
            case SocketConnect(actor) => {
              logger.info(command.logJson)
              Effect
                .persist(command)
                .thenRun((latest: State) => {
                  sharding
                    .entityRefFor(
                      OrderBase.typeKey,
                      OrderBase.typeKey.name
                    )
                    .tell(
                      Enable(state.data.mechineId)
                    )
                })
            }
            case SocketDisconnect() => {
              logger.info(command.logJson)
              timers.cancel(timeoutName)
              Effect
                .persist(command)
                .thenRun((latest: State) => {
                  sharding
                    .entityRefFor(
                      OrderBase.typeKey,
                      OrderBase.typeKey.name
                    )
                    .tell(
                      Disable(mechineId)
                    )
                })
            }
            case SocketScanned() => {
              logger.info(command.logJson)
              Effect.persist(command)
            }
            case SocketWechatPage() => {
              logger.info(command.logJson)
              Effect.persist(command)
            }
            case SocketQrcodeChoose() => {
              logger.info(command.logJson)
              Effect.persist(command)
            }
            case SocketQrcodeIdentify() => {
              logger.info(command.logJson)
              Effect.persist(command)
            }
            case SocketQrcodeUnIdentify(screen) => {
              logger.error(command.logJson)
              Effect.persist(command)
            }
            case SocketPaySuccess() => {
              timers.cancel(timeoutName)
              logger.info(command.logJson)
              Effect
                .persist(command)
                .thenRun((latest: State) => {
                  sharding
                    .entityRefFor(
                      OrderBase.typeKey,
                      OrderBase.typeKey.name
                    )
                    .tell(
                      OrderPaySuccess(latest.data.order.get)
                    )
                  sharding
                    .entityRefFor(
                      OrderBase.typeKey,
                      OrderBase.typeKey.name
                    )
                    .tell(
                      Enable(
                        latest.data.mechineId
                      )
                    )
                })

            }
            case SocketPayFail(screen) => {
              logger.error(command.logJson)
              timers.cancel(timeoutName)
              Effect
                .persist(command)
                .thenRun((latest: State) => {
                  sharding
                    .entityRefFor(
                      OrderBase.typeKey,
                      OrderBase.typeKey.name
                    )
                    .tell(
                      OrderPayFail(
                        latest.data.order.get,
                        getStatus(state)
                      )
                    )
                  sharding
                    .entityRefFor(
                      OrderBase.typeKey,
                      OrderBase.typeKey.name
                    )
                    .tell(
                      Enable(
                        latest.data.mechineId
                      )
                    )

                })
            }

            case e @ Shutdown() => {
              logger.info(command.logJson)
              Effect
                .stop()
                .thenRun((_: State) => {
                  e.replyTo.tell(ShutdownOk(e, mechineId))
                  sharding
                    .entityRefFor(
                      OrderBase.typeKey,
                      OrderBase.typeKey.name
                    )
                    .tell(
                      Disable(mechineId)
                    )
                })
            }
          }

        def eventDefaultHandler(
            state: State,
            command: BaseSerializer
        ): State = {
          command match {
            case SocketDisconnect() => {
              Disconnected(
                state.data.copy(
                  actor = None
                )
              )
            }
            case SocketScanned() => {
              Scanned(state.data)
            }
            case SocketWechatPage() => {
              WechatPage(state.data)
            }
            case SocketQrcodeChoose() => {
              QrcodeChoose(state.data)
            }
            case CreateOrder(order, mechineId) => {
              Connected(
                state.data.copy(
                  order = Option(order)
                )
              )
            }
            case SocketPaySuccess() => {
              PaySuccess(
                state.data.copy(
                  errors = state.data.errors.copy(
                    payFail = None
                  )
                )
              )
            }
            case SocketPayFail(screen) => {
              PayFail(
                state.data.copy(
                  errors = state.data.errors.copy(
                    payFail = Option(screen)
                  )
                )
              )
            }
            case SocketQrcodeUnIdentify(screen) => {
              QrcodeUnIdentify(
                state.data.copy(
                  errors = state.data.errors.copy(
                    qrcodeUnIdentify = Option(screen)
                  )
                )
              )
            }
            case SocketQrcodeIdentify() => {
              QrcodeIdentify(
                state.data.copy(
                  errors = state.data.errors.copy(
                    qrcodeUnIdentify = None
                  )
                )
              )
            }
            case SocketConnect(actor) => {
              Connected(
                state.data.copy(
                  actor = Option(actor),
                  order = Option.empty,
                  errors = ErrorInfo()
                )
              )
            }
            case SocketTimeout(screen) => {
              Timeout(
                state.data.copy(
                  order = None,
                  errors = state.data.errors.copy(
                    timeout = screen
                  )
                )
              )
            }
            case _ => throw new Exception(s"unknow command ${command}")
          }
        }

        val commandHandler: (
            State,
            BaseSerializer
        ) => Effect[BaseSerializer, State] = (state, command) =>
          statusList.find(_._3 == state.getClass) match {
            case Some(status) =>
              status._1(state, command, commandDefaultHandler)
            case None =>
              throw new Exception(s"status unknown -> ${state.getClass}")
          }

        val eventHandler: (State, BaseSerializer) => State =
          (state, command) =>
            statusList.find(_._3 == state.getClass) match {
              case Some(status) =>
                status._2(state, command, eventDefaultHandler)
              case None => throw new Exception("status unknown")
            }

        EventSourcedBehavior(
          persistenceId = entityId,
          emptyState = Disconnected(
            data = DataStore(
              mechineId = mechineId,
              actor = Option.empty,
              order = Option.empty,
              errors = ErrorInfo(),
              mechineTimeout = 50.seconds,
              appTimeout = 20.seconds
            )
          ),
          commandHandler = commandHandler,
          eventHandler = eventHandler
        ).onPersistFailure(
            backoffStrategy = SupervisorStrategy
              .restartWithBackoff(
                minBackoff = 1.seconds,
                maxBackoff = 10.seconds,
                randomFactor = 0.2
              )
              .withMaxRestarts(maxRestarts = 3)
              .withResetBackoffAfter(10.seconds)
          )
          .receiveSignal({
            case (state, RecoveryCompleted) =>
              logger.info(
                "Recovery Completed with state: {}",
                state.toJson
              )
            case (state, RecoveryFailed(err)) =>
              logger.error(
                "Recovery failed with: {}",
                err.getMessage
              )
            case (state, SnapshotCompleted(meta)) =>
              logger.debug(
                "Snapshot Completed with state: {},id({},{})",
                state,
                meta.persistenceId,
                meta.sequenceNr
              )
            case (state, SnapshotFailed(meta, err)) =>
              logger.error(
                "Snapshot failed with: {}",
                err.getMessage
              )
            case (_, PreRestart) =>
              logger.info(s"PreRestart")
            //              context.self.tell(Recovery())
            case (_, single) =>
              logger.debug(single.logJson)
          })
          .snapshotWhen((state, event, _) => true)
          .withRetention(
            criteria = RetentionCriteria
              .snapshotEvery(numberOfEvents = 2, keepNSnapshots = 1)
              .withDeleteEventsOnSnapshot
          )

      })

    }

}
