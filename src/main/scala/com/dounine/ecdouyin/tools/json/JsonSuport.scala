package com.dounine.ecdouyin.tools.json

import akka.actor.typed.ActorRef
import com.dounine.ecdouyin.model.types.router.ResponseCode
import com.dounine.ecdouyin.model.types.service.{
  AppPage,
  IntervalStatus,
  MechinePayStatus,
  MechineStatus,
  OrderStatus,
  PayPlatform,
  PayStatus
}
import org.json4s.JsonAST.{JField, JLong, JObject, JString}
import org.json4s.ext.EnumNameSerializer
import org.json4s.jackson.Serialization
import org.json4s.{
  CustomSerializer,
  DefaultFormats,
  Formats,
  JDouble,
  JInt,
  jackson
}

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.duration.{
  DAYS,
  FiniteDuration,
  HOURS,
  MICROSECONDS,
  MILLISECONDS,
  MINUTES,
  NANOSECONDS,
  SECONDS
}

object JsonSuport {

  object LocalDateTimeSerializer
      extends CustomSerializer[LocalDateTime](_ =>
        (
          {
            case JString(dateTime) => {
              if (dateTime.length == 10) {
                LocalDate.parse(dateTime).atStartOfDay()
              } else {
                LocalDateTime.parse(dateTime, dateTimeFormatter)
              }
            }
          },
          {
            case dateTime: LocalDateTime =>
              JString(
                dateTime.format(
                  DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                )
              )
          }
        )
      )

  object LocalDateSerializer
      extends CustomSerializer[LocalDate](_ =>
        (
          {
            case JString(date) =>
              LocalDate.parse(date)
          },
          {
            case date: LocalDate =>
              JString(
                date.toString
              )
          }
        )
      )

  object BigDecimalSerializer
      extends CustomSerializer[BigDecimal](_ =>
        (
          {
            case JString(value) => BigDecimal(value)
            case JDouble(value) => BigDecimal(value.toString)
            case JLong(value)   => BigDecimal(value.toString)
            case JInt(value)    => BigDecimal(value.toString)
          },
          {
            case value: BigDecimal =>
              JString(value.toString)
          }
        )
      )

  /**
    * only using for log serialaizer
    */
  object ActorRefSerializer
      extends CustomSerializer[ActorRef[_]](_ =>
        (
          {
            case JString(_) => null
          },
          {
            case actor: ActorRef[_] =>
              JString(actor.toString)
          }
        )
      )

  object FiniteDurationSerializer
      extends CustomSerializer[FiniteDuration](_ =>
        (
          {
            case JObject(
                  JField("unit", JString(unit)) :: JField(
                    "length",
                    JInt(length)
                  ) :: Nil
                ) =>
              FiniteDuration(length.toLong, unit)
            case JObject(
                  JField(
                    "length",
                    JInt(length)
                  ) :: JField("unit", JString(unit)) :: Nil
                ) =>
              FiniteDuration(length.toLong, unit)
          },
          {
            case time: FiniteDuration =>
              JObject(
                "unit" -> JString(time.unit match {
                  case DAYS         => "day"
                  case HOURS        => "hour"
                  case MINUTES      => "minute"
                  case SECONDS      => "second"
                  case MILLISECONDS => "millisecond"
                  case MICROSECONDS => "microsecond"
                  case NANOSECONDS  => "nanosecond"
                }),
                "length" -> JInt(time.length)
              )
          }
        )
      )

  val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd[' ']['T'][HH:mm[:ss[.SSS]]][X]")
  val serialization: Serialization.type = jackson.Serialization
  val formats: Formats = DefaultFormats +
    LocalDateTimeSerializer +
    LocalDateSerializer +
    BigDecimalSerializer +
    FiniteDurationSerializer ++ Seq(
    ResponseCode,
    IntervalStatus,
    PayStatus,
    PayPlatform,
    MechinePayStatus,
    MechineStatus,
    OrderStatus,
    AppPage
  ).map(new EnumNameSerializer(_))
}
