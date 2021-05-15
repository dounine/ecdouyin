package com.dounine.ecdouyin.behaviors.engine

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.stream._
import akka.stream.scaladsl.{
  Broadcast,
  Flow,
  GraphDSL,
  Keep,
  Merge,
  RunnableGraph,
  Sink,
  Source,
  SourceQueueWithComplete
}
import akka.{Done, NotUsed}
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel}
import com.dounine.ecdouyin.tools.json.{ActorSerializerSuport, JsonParse}
import com.dounine.ecdouyin.tools.util.DingDing
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.Promise
import scala.util.{Failure, Success}

object CoreEngine extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(CoreEngine.getClass)

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("CoreEngineBehavior")

  case class Message(message: BaseSerializer)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends BaseSerializer

  case class MessageOk(request: Message) extends BaseSerializer

  case class MessageFail(request: Message, msg: String) extends BaseSerializer

  case class CreateOrder(order: OrderModel.DbInfo)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends BaseSerializer

  case class CreateOrderOk(request: CreateOrder) extends BaseSerializer

  case class CreateOrderFail(request: CreateOrder, error: String)
      extends BaseSerializer

  case class CancelOrder(orderId: Long)(val replyTo: ActorRef[BaseSerializer])
      extends BaseSerializer

  case class CancelOrderOk(request: CancelOrder) extends BaseSerializer

  case class CancelOrderFail(request: CancelOrder, error: String)
      extends BaseSerializer

  case class Init() extends BaseSerializer

  case class Shutdown()(val replyTo: ActorRef[Done]) extends BaseSerializer

  case object ShutdownStream extends BaseSerializer

  def apply(
      entityId: PersistenceId
  ): Behavior[BaseSerializer] =
    Behaviors.setup[BaseSerializer] { context: ActorContext[BaseSerializer] =>
      {
        val appFlow: Flow[BaseSerializer, BaseSerializer, NotUsed] =
          AppSources.createCoreFlow(context.system)
        val orderFlow: Flow[BaseSerializer, BaseSerializer, NotUsed] =
          OrderSources.createCoreFlow(context.system)
        val qrcodeFlow: Flow[BaseSerializer, BaseSerializer, NotUsed] =
          QrcodeSources.createCoreFlow(context.system)

        implicit val materializer: Materializer = Materializer(context)

        val (
          (
            queue: SourceQueueWithComplete[BaseSerializer],
            shutdown: Promise[Option[BaseSerializer]]
          ),
          source: Source[BaseSerializer, NotUsed]
        ) = Source
          .queue[BaseSerializer](1000, OverflowStrategy.backpressure)
          .concatMat(Source.maybe[BaseSerializer])(Keep.both)
          .preMaterialize()

        RunnableGraph
          .fromGraph(GraphDSL.create(source) {
            implicit builder: GraphDSL.Builder[NotUsed] =>
              (request: SourceShape[BaseSerializer]) =>
                {
                  import GraphDSL.Implicits._
                  val broadcast
                      : UniformFanOutShape[BaseSerializer, BaseSerializer] =
                    builder.add(Broadcast[BaseSerializer](4))
                  val merge =
                    builder.add(Merge[BaseSerializer](5))
                  val init = Source.single(OrderSources.QueryOrderInit())

                  //                                           ┌─────────────┐
                  //                ┌──────┐            ┌─────▶□ Sink.ignore │
                  //                │ init │            │      └─────────────┘
                  //                └──□───┘            │         ┌───────┐
                  //                   │                ├────────▶□  app  □ ─────┐
                  //                   ▼                │         └───────┘      │
                  //┌─────────┐   ┌────□────┐     ┌─────□─────┐   ┌───────┐      │
                  //│ source  □─┐ │  merge  □ ──▶ □ broadcast │──▶□ order □ ─────┤
                  //└─────────┘ │ └──□─□─□─□┘     └─────□─────┘   └───────┘      │
                  //            │    ▲ ▲ ▲ ▲            │         ┌───────┐      │
                  //            │    │ │ │ │            └────────▶□qrcode □ ─────┤
                  //            │    │ │ │ │                      └───────┘      │
                  //            └────┘ └─┴─┴─────────────────────────────────────┘

                  request ~> merge ~> Flow[BaseSerializer].buffer(
                    100,
                    OverflowStrategy.fail
                  ) ~> broadcast ~> appFlow ~> merge.in(1)
                  broadcast ~> qrcodeFlow ~> merge.in(2)
                  broadcast ~> orderFlow ~> merge.in(3)
                  broadcast ~> Sink.ignore
                  merge.in(4) <~ init

                  ClosedShape
                }
          })
          .run()

        Behaviors.receiveMessage[BaseSerializer] {
          case e @ Init() => {
            Behaviors.same
          }
          case e @ CreateOrder(order) => {
            context.pipeToSelf(
              queue.offer(
                OrderSources.Create(
                  order
                )
              )
            ) {
              case Failure(exception) =>
                CreateOrderFail(e, exception.getMessage)
              case Success(value) => {
                value match {
                  case result: QueueCompletionResult => {
                    logger.error("系统异常 -> {}", e.logJson)
                    CreateOrderFail(e, "系统异常、请联系管理员")
                  }
                  case QueueOfferResult.Enqueued => {
                    CreateOrderOk(e)
                  }
                  case QueueOfferResult.Dropped => {
                    logger.error("操作太频繁 -> {}", e.logJson)
                    CreateOrderFail(e, "操作太频繁、请稍后再试")
                  }
                }
              }
            }
            Behaviors.same
          }
          case e @ CreateOrderOk(request) => {
            request.replyTo.tell(e)
            Behaviors.same
          }
          case e @ CreateOrderFail(request, error) => {
            request.replyTo.tell(e)
            Behaviors.same
          }
          case e @ MessageFail(request, msg) => {
            request.replyTo.tell(e)
            Behaviors.same
          }
          case e @ MessageOk(request) => {
            request.replyTo.tell(e)
            Behaviors.same
          }
          case e @ Message(message) => {
            context.pipeToSelf(
              queue.offer(message)
            ) {
              case Failure(exception) => MessageFail(e, exception.getMessage)
              case Success(value) => {
                value match {
                  case result: QueueCompletionResult => {
                    logger.error("系统异常 -> {}", message.logJson)
                    MessageFail(e, "系统异常、请联系管理员")
                  }
                  case QueueOfferResult.Enqueued => {
                    MessageOk(e)
                  }
                  case QueueOfferResult.Dropped => {
                    logger.error("操作太频繁 -> {}", message.logJson)
                    MessageFail(e, "操作太频繁、请稍后再试")
                  }
                }
              }
            }
            Behaviors.same
          }
          case request @ Shutdown() => {
            shutdown.trySuccess(Option(ShutdownStream))
            request.replyTo.tell(Done)
            Behaviors.same
          }
        }
      }.receiveSignal {
        case (context, PostStop) => {
          DingDing.sendMessage(
            DingDing.MessageType.system,
            data = DingDing.MessageData(
              markdown = DingDing.Markdown(
                title = "系统通知",
                text = s"""
                            |# CoreEngine 引擎退出
                            | - time: ${LocalDateTime.now()}
                            |""".stripMargin
              )
            ),
            context.system
          )
          Behaviors.stopped
        }
      }
    }

}
