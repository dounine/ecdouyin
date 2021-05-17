package com.dounine.ecdouyin.router.routers

import akka.http.scaladsl.common.{
  EntityStreamingSupport,
  JsonEntityStreamingSupport
}
import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpResponse,
  StatusCodes
}
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.StandardRoute
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.dounine.ecdouyin.model.models.RouterModel
import com.dounine.ecdouyin.model.models.RouterModel.Fail
import com.dounine.ecdouyin.model.types.router.ResponseCode
import com.dounine.ecdouyin.tools.json.JsonParse

trait SuportRouter extends JsonParse {

  val timeoutResponse = HttpResponse(
    StatusCodes.OK,
    entity = HttpEntity(
      ContentTypes.`application/json`,
      """{"code":"fail","msg":"service timeout"}"""
    )
  )

  /**
    * 将source转成单个son流、默认是以数组的方式输入单个或多个流、例如：[{"code":"ok"}]
    */
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
    EntityStreamingSupport
      .json()
      .withFramingRenderer(
        Flow[ByteString]
      )

  def ok(data: Any): StandardRoute = {
    complete(RouterModel.Data(Option(data)))
  }

  def okData(data: Any): RouterModel.Data = {
    RouterModel.Data(Option(data))
  }

  val ok: StandardRoute = complete(RouterModel.Ok())

  def fail(msg: String): StandardRoute = {
    complete(RouterModel.Fail(Option(msg)))
  }

  def failMsg(msg: String): Fail = {
    RouterModel.Fail(Option(msg))
  }
  def failDataMsg(data: Any, msg: String): RouterModel.Data = {
    RouterModel.Data(
      data = Some(data),
      msg = Some(msg),
      code = ResponseCode.fail
    )
  }

  val failData = RouterModel.Fail()
  val fail: StandardRoute = complete(failData)
}
