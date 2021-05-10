package com.dounine.ecdouyin.model.models

import com.dounine.ecdouyin.model.types.router.ResponseCode
import com.dounine.ecdouyin.model.types.router.ResponseCode.ResponseCode

object RouterModel {

  case class Data(
                   data: Option[Any] = Option.empty[Any],
                   code: ResponseCode = ResponseCode.ok
                 )

  case class Ok(
                 code: ResponseCode = ResponseCode.ok
               )

  case class Fail(
                   msg: Option[String] = Option.empty[String],
                   code: ResponseCode = ResponseCode.fail
                 )

}
