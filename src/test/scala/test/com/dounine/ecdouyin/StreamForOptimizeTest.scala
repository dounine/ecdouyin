package test.com.dounine.ecdouyin

import akka.{Done, NotUsed}
import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.event.LogMarker
import akka.stream.{
  Attributes,
  ClosedShape,
  Materializer,
  OverflowStrategy,
  RestartSettings,
  SourceShape,
  SystemMaterializer
}
import akka.stream.scaladsl.{
  Broadcast,
  Concat,
  Flow,
  GraphDSL,
  Keep,
  Merge,
  MergePreferred,
  RestartSource,
  RunnableGraph,
  Sink,
  Source,
  SourceQueueWithComplete,
  Unzip,
  Zip,
  ZipWith
}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.typed.scaladsl.ActorSource
import com.dounine.ecdouyin.model.models.BaseSerializer
import com.dounine.ecdouyin.store.EnumMappers
import com.dounine.ecdouyin.tools.json.JsonParse
import com.dounine.ecdouyin.tools.util.DingDing
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object QrcodeTest {
  def apply(): Behavior[String] = {
    Behaviors.setup { context =>
      {
        val materializer = Materializer(context)
        Source(1 to 10)
          .throttle(1, 1.seconds)
          .runForeach(println)(materializer)
        Behaviors.receiveMessage {
          case "stop" => {
            println("stop")
            Behaviors.stopped
          }
          case "hello" => {
            println("hello")
            Behaviors.same
          }
        }
      }
    }
  }

}
case class HelloSet(name: String, age: Int) {
  override def hashCode() = name.hashCode

  override def equals(obj: Any) = {
    if (obj == null) {
      false
    } else {
      obj.asInstanceOf[HelloSet].name == name
    }
  }
}
sealed trait StreamEvent
sealed trait AppEvent extends StreamEvent
case class AppOnline() extends AppEvent
case class AppOffline() extends AppEvent
sealed trait OrderEvent extends StreamEvent
case class OrderInit() extends OrderEvent
case class OrderRequest() extends OrderEvent
case class OrderRequestOk() extends OrderEvent

object FlowLog {
  implicit class FlowLog(data: Flow[StreamEvent, StreamEvent, NotUsed])
      extends JsonParse {
    def log(name: String): Flow[StreamEvent, StreamEvent, NotUsed] = {
      data
        .logWithMarker(
          s" ******************* ",
          (e: StreamEvent) =>
            LogMarker(
              name = s"${name}Marker"
            ),
          (e: StreamEvent) => e.logJson
        )
        .withAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Error
          )
        )

    }
  }
}

class StreamForOptimizeTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25520
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          StreamForOptimizeTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          StreamForOptimizeTest
        ].getSimpleName}"
                      |""".stripMargin)
        .withFallback(
          ConfigFactory.parseResources("application-test.conf")
        )
        .resolve()
    )
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with EnumMappers
    with MockitoSugar {

  implicit val ec = system.executionContext

  "stream optimize" should {

    "dingding" in {
      DingDing.sendMessage(
        DingDing.MessageType.system,
        data = DingDing.MessageData(
          markdown = DingDing.Markdown(
            title = "系统通知",
            text = s"""
                |# 这是支持markdown的文本
                | - apiKey: hello
                | - account: hi
                | - money: 6
                |
                |time -> ${LocalDateTime.now()}
                |""".stripMargin
          )
        ),
        system
      )

      TimeUnit.SECONDS.sleep(1)
    }

    "graph single repeat" ignore {
      val source = Source(1 to 3)
      RunnableGraph
        .fromGraph(GraphDSL.create() { implicit builder =>
          import GraphDSL.Implicits._

//          val merge = builder.add(Merge[Int](2))
          val zip = builder.add(ZipWith((left: Int, right: Int) => {
            println(s"zip -> ${left}:${right}")
            left
          }))
          val broadcast = builder.add(Broadcast[Int](2))
          val start = Source.single(0)
          val concat = builder.add(Concat[Int]())

          source ~> zip.in0
          zip.out ~> Flow[Int]
            .throttle(1, 1.seconds)
            .map(i => {
              println(i); i * 10
            }) ~> broadcast ~> Sink.foreach[Int](i => {
            println(s"result -> ${i}")
          })
          zip.in1 <~ concat <~ start
          concat <~ broadcast

//          source ~> merge ~> Flow[Int]
//            .throttle(1, 1.seconds)
//            .map(i => { println(i); i }) ~> broadcast
//          merge <~ Flow[Int].buffer(2, OverflowStrategy.fail) <~ broadcast

          ClosedShape
        })
        .run()

      TimeUnit.SECONDS.sleep(30)
    }

    "source concat" ignore {

      val a = Source(1 to 3)
      val b = Source(7 to 9)
        .throttle(1, 10.seconds)

      a.merge(b).runForeach(i => { info(i.toString) })
      TimeUnit.SECONDS.sleep(4)
    }

    "graph dsl" ignore {

      import FlowLog.FlowLog

      val flowApp = Flow[StreamEvent]
        .collectType[AppEvent]
        .statefulMapConcat { () =>
          {
            case AppOnline()  => Nil
            case AppOffline() => Nil
          }
        }

      val flowOrder = Flow[StreamEvent]
        .collectType[OrderEvent]
        .log("order")
        .statefulMapConcat { () =>
          {
            case OrderInit() => {
              OrderRequest() :: Nil
            }
            case OrderRequestOk() => Nil
            case OrderRequest() => {
              println("error")
              Nil
            }
          }
        }
        .log("order")
        .mapAsync(1) {
          case OrderRequest() => Future.successful(OrderRequestOk())
          case ee             => Future.successful(ee)
        }

      val (
        queue: SourceQueueWithComplete[StreamEvent],
        source: Source[StreamEvent, NotUsed]
      ) = Source
        .queue[StreamEvent](8, OverflowStrategy.backpressure)
        .preMaterialize()
      val graph = RunnableGraph.fromGraph(GraphDSL.create(source) {
        implicit builder: GraphDSL.Builder[NotUsed] =>
          (request: SourceShape[StreamEvent]) =>
            {
              import GraphDSL.Implicits._

              val broadcast = builder.add(Broadcast[StreamEvent](2))
              val merge = builder.add(Merge[StreamEvent](3))
              //                                         ┌───────┐
              //                               ┌────────▶□  app  □──┐
              //                               │         └───────┘  │
              //         ┌─────────┐     ┌─────□─────┐              │
              //         │  merge  □  ─▶ □ broadcast │              │
              //         └──□─□─□──┘     └─────□─────┘              │
              //            ▲ ▲ ▲              │         ┌───────┐  │
              //            │ │ │              └────────▶□ order │  │
              //┌─────────┐ │ │ │                        └───□───┘  │
              //│ request □─┘ │ └────────────────────────────┘      │
              //└─────────┘   └─────────────────────────────────────┘

              request ~> merge ~> broadcast ~> flowApp ~> merge.in(1)
              broadcast ~> flowOrder ~> merge.in(2)

              ClosedShape
            }

      })
      graph.run()
      queue.offer(OrderInit())
      TimeUnit.SECONDS.sleep(1)
    }

    "stream split" ignore {
      Source(1 to 3)
        .splitWhen(_ => true)
        .map(i => {
          println("流1 -> " + i)
          i
        })
        .mergeSubstreams
        .runForeach(i => {
          info(i.toString)
        })
      TimeUnit.SECONDS.sleep(1)
    }

    "test staful" ignore {
      val (
        (
          queue: SourceQueueWithComplete[String],
          shutdown: Promise[Option[Nothing]]
        ),
        source: Source[String, NotUsed]
      ) =
        Source
          .queue[String](
            bufferSize = 8,
            overflowStrategy = OverflowStrategy.backpressure
          )
          .concatMat(Source.maybe)(Keep.both)
          .preMaterialize()

      queue.offer("init")

      source
        .logWithMarker(
          " **************** ",
          (e: String) =>
            LogMarker(
              name = "marker"
            ),
          (e: String) => e
        )
        .addAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Error
          )
        )
        .via(
          Flow[String]
            .flatMapConcat {
              case "query" => Source.single("queryOk")
              case ee      => Source.single(ee)
            }
            .statefulMapConcat { () =>
              {
                case "init" => "query" :: Nil
//                case "query"     => "queryOk" :: Nil
                case "queryOk"   => Nil
                case "queryFail" => Nil
                case "ignore"    => Nil
                case ee          => ee :: Nil
              }
            }

          //            .mapAsync(1) {
          //              case "query" => Future.successful("queryOk")
          //              case ee      => Future.successful(ee)
          //            }
          //            .flatMapConcat{
//              case "2" => Source.single("4")
//              case ee => Source.single(ee)
//            }
        )
        .runForeach(result => {
          println("---------- >" + result)
          queue.offer(result)
//          if (result == "3") {
//            shutdown.success(None)
//          }
        })
//        .futureValue shouldBe Done

      TimeUnit.SECONDS.sleep(1)
      shutdown.trySuccess(None)
    }

    "set case class equals" ignore {
      val list = Set(HelloSet("a", 1), HelloSet("b", 2), HelloSet("a", 2))
      list.size shouldBe 2
    }

    "log marker" ignore {
      Source(1 to 3)
        .log("payStream")
        .addAttributes(
          attr = Attributes.logLevels(
            onElement = Attributes.LogLevels.Info
          )
        )
        .runForeach(i => {
          info(i.toString)
        })
        .futureValue shouldBe Done
    }
    "staful test " ignore {
      Source
        .single(1)
        .statefulMapConcat {
          () =>
            { el =>
              Array(1, 2, 3, 4)
            }
        }
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2, 3, 4)

    }

    "source form actor" ignore {
//      val ref = ActorSource.actorRefWithBackpressure[String,String](
//        completionMatcher = {
//          case "finish" =>
//        },
//        failureMatcher = {
//          case "error" => new Exception("error")
//        },
//        bufferSize = 2,
//        overflowStrategy = OverflowStrategy.backpressure
//      )
//        .to(Sink.foreach(i => {
//          TimeUnit.SECONDS.sleep(3)
//          println(i)
//        }))
//        .run()
//
//      ref ! "hello"
//      ref ! "hello"
//      ref ! "hello"
//      ref ! "hello"
    }

    "actor and source test" ignore {
      val behavior = system.systemActorOf(QrcodeTest(), "hello")
      behavior.tell("hello")
      TimeUnit.SECONDS.sleep(2)
      behavior.tell("stop")
      TimeUnit.SECONDS.sleep(15)
    }

    "multi source terminal" ignore {
      Source
        .single(1)
        .map(i => i)
        .flatMapConcat { source =>
          Source(1 to 10)
        }
        .watchTermination()((pv, future) => {
          future.foreach(_ => {
            info("done")
          })
          pv
        })
        .logWithMarker(
          "mystream",
          e =>
            LogMarker(
              name = "myMarker",
              properties = Map("element" -> e)
            )
        )
        .addAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Info
          )
        )
        .runWith(Sink.last)
        .futureValue shouldBe 2
    }

    "finish error" ignore {
      Source
        .single(1)
        .map(f => {
          if (f == 1) {
            throw new Exception("error")
          } else f
        })

      val cc = Source(1 to 5)
        .watchTermination()((prevMatValue, future) => {
          future.onComplete {
            case Failure(exception) => println(exception.getMessage)
            case Success(_)         => println(s"The stream materialized $prevMatValue")
          }
        })

    }

    "error throw" ignore {
      Source(1 to 3)
        .mapAsync(1) { i =>
          Future {
            if (i == 1) {
              throw new Exception("error")
            } else i
          }.recover {
            case _ => throw new Exception("error1")
          }
        }
        .mapAsync(1) { i =>
          Future {
            if (i == 1) {
              throw new Exception("erro2")
            }
          }.recover {
            case _ => throw new Exception("error3")
          }
        }
        .recover {
          case e => {
            println(e.getMessage)
          }
        }
        .runForeach(println)
    }

    "future transform" ignore {
      Source
        .single(1)
        .mapAsync(1) { i =>
          Future {
            if (i == 1) {
              throw new Exception("error")
            } else i
          }.recover {
            case _ => throw new Exception("无法点击按钮")
          }
        }
        .recover {
          case e => e.getMessage == "无法点击按钮"
        }
        .runWith(Sink.head)
        .futureValue shouldBe true
    }
    "map error" ignore {
      val result = Source(1 to 3)
        .map(i => {
          if (i == 1) {
            throw new Exception("error")
          } else i
        })
        .mapError {
          case e: Exception => throw new RuntimeException("第一层错误")
        }
        .map(_ * 2)
        .mapError {
          case e: Exception => new RuntimeException("第二层错误")
        }
        .runWith(Sink.head)
        .futureValue

      info(result.toString)
    }
    "either test" ignore {
      Source
        .single("success")
        .delay(500.milliseconds)
        .map(Right.apply)
        .idleTimeout(200.milliseconds)
        .recover {
          case e: Throwable => Left(e)
        }
        .runWith(Sink.head)
        .futureValue match {
        case Left(value)  => info(value.getMessage)
        case Right(value) => info(value)
      }
    }
  }
}
