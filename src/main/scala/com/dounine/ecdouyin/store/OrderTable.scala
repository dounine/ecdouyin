package com.dounine.ecdouyin.store

import com.dounine.ecdouyin.model.models.OrderModel
import com.dounine.ecdouyin.model.types.service.MechinePayStatus.MechinePayStatus
import com.dounine.ecdouyin.model.types.service.{PayPlatform, PayStatus}
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.model.types.service.PayStatus.PayStatus
import slick.jdbc.MySQLProfile.api._
import slick.lifted.{PrimaryKey, ProvenShape}

import java.time.LocalDateTime

class OrderTable(tag: Tag)
    extends Table[OrderModel.DbInfo](tag, _tableName = "ecdouyin_order")
    with EnumMappers {

  override def * : ProvenShape[OrderModel.DbInfo] =
    (
      orderId,
      outOrder,
      apiKey,
      account,
      money,
      volumn,
      margin,
      platform,
      status,
      mechineStatus,
      payCount,
      createTime
    ).mapTo[OrderModel.DbInfo]

  def orderId: Rep[Long] =
    column[Long]("orderId", O.AutoInc, O.PrimaryKey, O.Length(32))

  def outOrder: Rep[String] =
    column[String]("outOrder", O.Unique, O.Length(32))

  def apiKey: Rep[String] = column[String]("apiKey", O.Length(32))

  def account: Rep[String] = column[String]("account", O.Length(50))

  def money: Rep[Int] = column[Int]("money", O.Length(11))

  def payCount: Rep[Int] = column[Int]("payCount", O.Length(11))

  def volumn: Rep[Int] = column[Int]("volumn", O.Length(11))

  def margin: Rep[BigDecimal] =
    column[BigDecimal]("margin", O.SqlType("decimal(10, 2)"))

  def platform: Rep[PayPlatform] =
    column[PayPlatform]("platform", O.Length(PayPlatform.dbLength))

  def status: Rep[PayStatus] =
    column[PayStatus]("status", O.Length(PayStatus.dbLength))

  def mechineStatus: Rep[MechinePayStatus] = column[MechinePayStatus]("mechineStatus", O.Length(20))

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime]("createTime", O.SqlType("timestamp"), O.Length(3))(
      localDateTime2timestamp
    )

}
