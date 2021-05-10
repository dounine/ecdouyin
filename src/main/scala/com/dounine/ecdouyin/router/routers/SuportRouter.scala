package com.dounine.ecdouyin.router.routers

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.StandardRoute
import com.dounine.ecdouyin.model.models.RouterModel
import com.dounine.ecdouyin.tools.json.JsonParse

trait SuportRouter extends JsonParse {

  val timeoutResponse = HttpResponse(
    StatusCodes.OK,
    entity = HttpEntity(ContentTypes.`application/json`, """{"code":"fail","msg":"service timeout"}""")
  )

  def ok(data: Any): StandardRoute = {
    complete(RouterModel.Data(Option(data)))
  }

  val ok: StandardRoute = complete(RouterModel.Ok())

  def fail(msg: String): StandardRoute = {
    complete(RouterModel.Fail(Option(msg)))
  }

  val fail: StandardRoute = complete(RouterModel.Fail())


}
