package test.com.dounine.ecdouyin

import akka.Done
import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{
  Attributes,
  FlowShape,
  Inlet,
  Materializer,
  Outlet,
  RestartSettings,
  SinkShape,
  SourceShape
}
import com.dounine.ecdouyin.model.models.{OrderModel, UserModel}
import com.dounine.ecdouyin.model.types.service.{PayPlatform, PayStatus}
import com.dounine.ecdouyin.service.OrderStream
import com.dounine.ecdouyin.store.{EnumMappers, OrderTable, UserTable}
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

class NumberSource extends GraphStage[SourceShape[Int]] {
  val out: Outlet[Int] = Outlet[Int]("numberSource.out")
  override def createLogic(inheritedAttributes: Attributes) =
    new GraphStageLogic(shape) {

      var count: Int = 0
      this.setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = {
            push(out, count)
            count += 1
          }
        }
      )
    }

  override def shape = SourceShape(out)
}
class StdoutSink extends GraphStage[SinkShape[Int]] {
  val in = Inlet[Int]("stdout.in")
  override def createLogic(inheritedAttributes: Attributes) =
    new GraphStageLogic(shape) {

      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val element = grab(in)
            println(element)
            pull(in)
          }
        }
      )

      override def preStart(): Unit = pull(in)
    }

  override def shape = SinkShape(in)
}
class MyMap[A, B](f: A => B) extends GraphStage[FlowShape[A, B]] {
  val in = Inlet[A]("myMap.in")
  val out = Outlet[B]("myMap.out")
  override def createLogic(inheritedAttributes: Attributes) =
    new GraphStageLogic(shape) {

      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val outValue = f(grab(in))
            push(out, outValue)
          }
        }
      )
      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = pull(in)
        }
      )
    }

  override def shape = FlowShape.of(in, out)
}
class CustomStreamTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25521
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          CustomStreamTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          CustomStreamTest
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
  implicit val materializer = Materializer(system)

  "custom stream" should {

    "range for source" in {
      Source(1 to 4)
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2, 3, 4)
    }

    "source stream" ignore {
      Source
        .fromGraph(new NumberSource())
        .via(new MyMap(f => f + 1))
        .take(2)
        .runWith(new StdoutSink())

      TimeUnit.SECONDS.sleep(2)
    }

  }
}
