package com.dounine.ecdouyin.model.models

import com.dounine.ecdouyin.model.types.router.ResponseCode
import com.dounine.ecdouyin.model.types.router.ResponseCode.ResponseCode

object RouterModel {

  sealed trait JsonData

  case class Data(
      data: Option[Any] = None,
      msg: Option[String] = None,
      code: ResponseCode = ResponseCode.ok
  ) extends JsonData

  case class Ok(
      code: ResponseCode = ResponseCode.ok
  ) extends JsonData

  case class Fail(
      msg: Option[String] = None,
      code: ResponseCode = ResponseCode.fail
  ) extends JsonData

}
