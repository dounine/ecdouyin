package com.dounine.ecdouyin.router.routers

import com.dounine.ecdouyin.model.models.UserModel
import com.dounine.ecdouyin.service.UserService
import sangria.schema._
import sangria.macros.derive._

import java.time.LocalDateTime

object SchemaDef {

  implicit val UserTable = ObjectType(
    name = "User",
    description = "用户信息",
    fields = fields[Unit, UserModel.DbInfo](
      Field(
        name = "apiKey",
        fieldType = StringType,
        description = Some("钥匙"),
        resolve = _.value.apiKey
      ),
      Field(
        name = "balance",
        fieldType = BigDecimalType,
        description = Some("余额"),
        resolve = _.value.balance
      ),
      Field(
        name = "margin",
        fieldType = BigDecimalType,
        description = Some("冻结金额"),
        resolve = _.value.margin
      ),
      Field(
        name = "apiSecret",
        fieldType = StringType,
        description = Some("密钥"),
        resolve = _.value.apiSecret
      ),
      Field(
        name = "callback",
        fieldType = OptionType(StringType),
        description = Some("回调地扯"),
        resolve = _.value.callback
      ),
      Field(
        name = "createTime",
        fieldType = StringType,
        description = Some("创建时间"),
        resolve = _.value.createTime.toString
      )
    )
  )

//  implicit val UserType: ObjectType[Unit, UserModel.DbInfo] =
//    deriveObjectType[Unit, UserModel.DbInfo](
//      Interfaces(Userable),
//      IncludeFields("apiKey", "密钥"),
//      IncludeFields("balance", "余额"),
//      IncludeFields("margin", "冻结金额")
//    )

  val QueryType = ObjectType(
    name = "UserQuery",
    description = "用户信息",
    fields = fields[UserService, Unit](
      Field(
        name = "info",
        fieldType = OptionType(UserTable),
        description = Some("单个用户信息查询"),
        arguments = Argument(
          name = "apiKey",
          argumentType = StringType,
          description = "密钥"
        ) :: Nil,
        resolve = c =>
          Option(
            UserModel.DbInfo(
              apiKey = "abc",
              apiSecret = "abc",
              balance = BigDecimal("0.00"),
              margin = BigDecimal("0.00"),
              callback = Option.empty,
              createTime = LocalDateTime.now()
            )
          ) //c.ctx.info(c.args.arg[String]("apiKey"))
      )
    )
  )

  val UserSchema = Schema(QueryType)

}
