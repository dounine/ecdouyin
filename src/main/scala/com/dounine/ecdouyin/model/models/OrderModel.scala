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
      payCount: Int,
      createTime: LocalDateTime
  ) extends BaseSerializer {
    override def hashCode(): Int = orderId.hashCode()

    override def equals(obj: Any): Boolean = {
      if (obj == null) {
        false
      } else {
        obj.asInstanceOf[DbInfo].orderId == orderId
      }
    }
  }

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

  final case class UserInfo(
      nickName: String,
      id: String,
      avatar: String
  ) extends BaseSerializer

  final case class DouYinSearchAvatarThumb(
      avg_color: String,
      height: Long,
      image_type: Int,
      is_animated: Boolean,
      open_web_url: String,
      uri: String,
      url_list: Seq[String],
      width: Long
  )

  final case class DouYinSearchOpenInfo(
      avatar_thumb: DouYinSearchAvatarThumb,
      nick_name: String,
      search_id: String
  )

  final case class DouYinSearchData(
      open_info: Seq[DouYinSearchOpenInfo]
  )

  final case class DouYinSearchResponse(
      status_code: Int,
      data: DouYinSearchData
  )

}
