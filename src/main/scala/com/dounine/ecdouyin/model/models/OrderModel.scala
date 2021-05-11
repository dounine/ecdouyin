package com.dounine.ecdouyin.model.models

import com.dounine.ecdouyin.model.types.service.MechinePayStatus.MechinePayStatus
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.model.types.service.PayStatus.PayStatus
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive

import java.time.LocalDateTime

object OrderModel {

  final case class DbInfo(
      orderId: Long,
      outOrder: String,
      apiKey: String,
      account: String,
      money: Int,
      volumn: Int,
      margin: BigDecimal,
      platform: PayPlatform,
      status: PayStatus,
      mechineStatus: MechinePayStatus,
      payCount: Int,
      createTime: LocalDateTime
  ) extends BaseSerializer

  final case class Recharge(
      apiKey: String,
      account: String,
      money: String,
      platform: PayPlatform,
      outOrder: String,
      sign: String
  ) extends BaseSerializer

  final case class Cancel(
      apiKey: String,
      orderId: Option[String],
      outOrder: Option[String],
      sign: String
  ) extends BaseSerializer

  final case class Query(
      apiKey: String,
      orderId: Option[String],
      outOrder: Option[String],
      sign: String
  ) extends BaseSerializer

  final case class Balance(
      apiKey: String,
      sign: String
  ) extends BaseSerializer

  final case class CallbackInfo(
      apiKey: String,
      orderId: String,
      outOrder: String,
      money: String,
      account: String,
      platform: PayPlatform,
      status: PayStatus,
      sign: String,
      msg: Option[String]
  ) extends BaseSerializer

}
