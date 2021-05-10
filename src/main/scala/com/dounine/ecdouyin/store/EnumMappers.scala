package com.dounine.ecdouyin.store

import com.dounine.ecdouyin.model.types.service.{
  IntervalStatus,
  MechinePayStatus,
  PayPlatform,
  PayStatus
}
import com.dounine.ecdouyin.model.types.service.IntervalStatus.IntervalStatus
import com.dounine.ecdouyin.model.types.service.MechinePayStatus.MechinePayStatus
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.model.types.service.PayStatus.PayStatus
import com.dounine.ecdouyin.tools.json.JsonParse
import slick.ast.BaseTypedType
import slick.jdbc.JdbcType
import slick.jdbc.MySQLProfile.api._

import java.sql.{Date, Timestamp}
import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.duration.FiniteDuration

trait EnumMappers extends JsonParse {

  val localDateTime2timestamp
      : JdbcType[LocalDateTime] with BaseTypedType[LocalDateTime] =
    MappedColumnType.base[LocalDateTime, Timestamp](
      { instant: LocalDateTime =>
        if (instant == null) null else Timestamp.valueOf(instant)
      },
      { timestamp: Timestamp =>
        if (timestamp == null) null else timestamp.toLocalDateTime
      }
    )

  implicit val finiteDuration2String
      : JdbcType[FiniteDuration] with BaseTypedType[FiniteDuration] =
    MappedColumnType.base[FiniteDuration, String](
      e => e.toString,
      s => {
        val spl: Array[String] = s.split(" ")
        FiniteDuration(spl.head.toLong, spl.last)
      }
    )

  val localDate2timestamp: JdbcType[LocalDate] with BaseTypedType[LocalDate] =
    MappedColumnType.base[LocalDate, Date](
      { instant =>
        if (instant == null) null else Date.valueOf(instant)
      },
      { d =>
        if (d == null) null else d.toLocalDate
      }
    )

  implicit val mapString
      : JdbcType[Map[String, String]] with BaseTypedType[Map[String, String]] =
    MappedColumnType.base[Map[String, String], String](
      e => {
        e.map(item => s"${item._1}:${item._2}").mkString(",")
      },
      s => {
        s.split(",")
          .map(_.split(":"))
          .map(keys => {
            (keys.head, keys.last)
          })
          .toMap
      }
    )

  implicit val intervalMapper
      : JdbcType[IntervalStatus] with BaseTypedType[IntervalStatus] =
    MappedColumnType.base[IntervalStatus, String](
      e => e.toString,
      s => IntervalStatus.withName(s)
    )

  implicit val payPlatformMapper
      : JdbcType[PayPlatform] with BaseTypedType[PayPlatform] =
    MappedColumnType.base[PayPlatform, String](
      e => e.toString,
      s => PayPlatform.withName(s)
    )

  implicit val payStatusMapper
      : JdbcType[PayStatus] with BaseTypedType[PayStatus] =
    MappedColumnType.base[PayStatus, String](
      e => e.toString,
      s => PayStatus.withName(s)
    )

  implicit val mechinePayStatusMapper
      : JdbcType[MechinePayStatus] with BaseTypedType[MechinePayStatus] =
    MappedColumnType.base[MechinePayStatus, String](
      e => e.toString,
      s => MechinePayStatus.withName(s)
    )

}
