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
  Materializer,
  OverflowStrategy,
  RestartSettings,
  SystemMaterializer
}
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.typed.scaladsl.ActorSource
import com.dounine.ecdouyin.store.EnumMappers
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
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
