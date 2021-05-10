package com.dounine.ecdouyin.store

import com.dounine.ecdouyin.model.models.UserModel
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ProvenShape

import java.time.LocalDateTime

class UserTable(tag: Tag)
    extends Table[UserModel.DbInfo](tag, _tableName = "ecdouyin_user")
    with EnumMappers {

  override def * : ProvenShape[UserModel.DbInfo] =
    (
      apiKey,
      apiSecret,
      balance,
      margin,
      createTime
    ).mapTo[UserModel.DbInfo]

  def apiKey: Rep[String] = column[String]("apiKey", O.PrimaryKey, O.Length(32))

  def apiSecret: Rep[String] = column[String]("apiSecret", O.Length(32))

  def balance: Rep[BigDecimal] = column[BigDecimal]("balance", O.Length(11))

  def margin: Rep[BigDecimal] = column[BigDecimal]("margin", O.Length(11))

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime]("createTime", O.SqlType("timestamp"), O.Length(3))(
      localDateTime2timestamp
    )

}
