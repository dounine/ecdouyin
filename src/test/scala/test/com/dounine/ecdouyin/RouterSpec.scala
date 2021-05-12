package test.com.dounine.ecdouyin
import akka.http.scaladsl.model.{
  HttpEntity,
  HttpMethods,
  HttpRequest,
  MediaTypes,
  StatusCodes
}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.server._
import Directives._
import com.dounine.ecdouyin.model.models.OrderModel
import com.dounine.ecdouyin.tools.json.JsonParse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import eu.timepit.refined.string.MatchesRegex
import io.circe.{Decoder, Encoder}
import io.circe.refined._
import io.circe._
import test.com.dounine.ecdouyin.Helloc.Hello


object Helloc {
  import com.holidaycheck.akka.http.RefinedUnmarshaller._
  type Age = Int Refined Positive
  type Name = String Refined MatchesRegex[W.`"^[a-z]{2}$"`.T]
  case class Hello(name: Name, age: Age)
}
class RouterSpec
    extends AnyWordSpec
    with Matchers
    with ScalatestRouteTest
    with JsonParse {
  type Limit = Int Refined Positive
  val smallRoute =
    post {
      concat(
        entity(as[Hello]) { data =>
          complete(data.toJson)
        }
      )
    }

  "The service" should {

    "get test" in {
      HttpRequest(
        uri = "/",
        method = HttpMethods.POST,
        entity = HttpEntity(
          contentType = MediaTypes.`application/json`,
          string = """{"name":"lake","age":-18}"""
        )
      ) ~> smallRoute ~> check {
        responseAs[String] shouldBe "ok"
      }
    }

//    "return a 'PONG!' response for GET requests to /ping" in {
//      // tests:
//      Get("/ping") ~> smallRoute ~> check {
//        responseAs[String] shouldEqual "PONG!"
//      }
//    }
//
//    "leave GET requests to other paths unhandled" in {
//      // tests:
//      Get("/kermit") ~> smallRoute ~> check {
//        handled shouldBe false
//      }
//    }
//
//    "return a MethodNotAllowed error for PUT requests to the root path" in {
//      // tests:
//      Put() ~> Route.seal(smallRoute) ~> check {
//        status shouldEqual StatusCodes.MethodNotAllowed
//        responseAs[
//          String
//        ] shouldEqual "HTTP method not allowed, supported methods: GET"
//      }
//    }
  }
}
