package test.com.dounine.ecdouyin

import akka.{Done, NotUsed}
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.{Materializer, RestartSettings, SystemMaterializer}
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
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
class StreamForOptimizeTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25521
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

    "actor and source test" in {
      val behavior = system.systemActorOf(QrcodeTest(), "hello")
      behavior.tell("hello")
      TimeUnit.SECONDS.sleep(2)
      behavior.tell("stop")
      TimeUnit.SECONDS.sleep(15)
    }

    "multi source terminal" ignore {
      val a = Source
        .single(1)
        .map(i => i)
        .watchTermination()((pv, future) => {
          future.onComplete {
            case Failure(exception) => throw exception
            case Success(value)     => println("======= error1")
          }
          pv
        })

      a.map(_ * 2)
        .watchTermination()((pv, future) => {
          future.onComplete {
            case Failure(exception) => throw exception
            case Success(value)     => println("------ error2")
          }
          pv
        })
        .run()
        .futureValue shouldBe Done
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
