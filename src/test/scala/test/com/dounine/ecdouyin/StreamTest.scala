package test.com.dounine.ecdouyin

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.RestartSettings
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import com.dounine.ecdouyin.store.EnumMappers
import com.dounine.ecdouyin.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.{AnyWordSpec, AnyWordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util.{Failure, Success}
class StreamTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25521
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          StreamTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          StreamTest
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

  "stream" should {
    "stream log test" ignore {
      Source(1 to 3)
        .map(i => {
          if (i == 2) {
            throw new Exception(s"${i} error")
          }
          i
        })
        .recover {
          case e: Throwable => {
            println("第一层异常")
            throw e
          }
        }
        .log("异常1")
        .map(_ * 2)
        .recover {
          case e: Throwable => {
            println("第二层异常")
            throw e
          }
        }
        .log("异常2")
        .runForeach(println)
        .onComplete {
          case Failure(exception) => throw exception
          case Success(value)     => println(value)
        }
    }
    "demo" ignore {
      val flakySource =
        Source(
          1 to 3
        ).map(i => {
          if (i == 2) {
            throw new Exception("error")
          }
          i
        })

      val forever =
        RestartSource.onFailuresWithBackoff(
          RestartSettings(
            minBackoff = 1.second,
            maxBackoff = 10.seconds,
            randomFactor = 0.1
          )
        )(() => flakySource)
      forever.runWith(Sink.foreach(nr => system.log.info("{}", nr))).futureValue

    }
    "restart source" ignore {
      RestartSource
        .onFailuresWithBackoff(
          RestartSettings(
            minBackoff = 1.seconds,
            maxBackoff = 3.seconds,
            randomFactor = 0
          )
        )(() => {
          println(LocalDateTime.now())
          Source
            .single(1)
            .map(i => {
              if (i > 0) {
                throw new Exception("error")
              } else i
            })
        })
        .runForeach(i => {
          println(11)
        })
        .onComplete {
          case Failure(exception) => throw exception
          case Success(value)     => println(value)
        }(system.executionContext)
      TimeUnit.SECONDS.sleep(30)
    }
  }
}
