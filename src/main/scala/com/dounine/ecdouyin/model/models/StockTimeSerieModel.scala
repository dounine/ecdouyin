package com.dounine.ecdouyin.model.models

import com.dounine.ecdouyin.model.types.service.IntervalStatus.IntervalStatus

import java.time.LocalDateTime

object StockTimeSerieModel {

  final case class Meta(
      symbol: String,
      interval: Option[IntervalStatus],
      currency: Option[String],
      exchange_timezone: Option[String],
      exchange: Option[String],
      `type`: Option[String]
  ) extends BaseSerializer

  final case class Info(
      datetime: LocalDateTime,
      open: BigDecimal,
      high: BigDecimal,
      low: BigDecimal,
      close: BigDecimal,
      volume: BigDecimal
  ) extends BaseSerializer

  final case class DBInfo(
      symbol: String,
      interval: IntervalStatus,
      datetime: LocalDateTime,
      open: BigDecimal,
      high: BigDecimal,
      low: BigDecimal,
      close: BigDecimal,
      volume: BigDecimal
  ) extends BaseSerializer

  final case class SimpleInfo(
      t: LocalDateTime,
      o: BigDecimal,
      h: BigDecimal,
      l: BigDecimal,
      c: BigDecimal,
      v: BigDecimal
  ) extends BaseSerializer

  final case class Response(
      meta: Option[Meta],
      code: Option[Int],
      message: Option[String],
      status: Option[String],
      values: Option[List[Info]]
  ) extends BaseSerializer

  final case class FutunItem(
      k: Long,
      o: BigDecimal,
      c: BigDecimal,
      h: BigDecimal,
      l: BigDecimal,
      v: BigDecimal
  ) extends BaseSerializer

  final case class FutunData(
      list: Seq[FutunItem]
  ) extends BaseSerializer

  final case class FutunResponse(
      code: Int,
      message: String,
      data: FutunData
  ) extends BaseSerializer

}
