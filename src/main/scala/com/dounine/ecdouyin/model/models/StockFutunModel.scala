package com.dounine.ecdouyin.model.models

import com.dounine.ecdouyin.model.types.service.IntervalStatus.IntervalStatus

import java.time.LocalDateTime

object StockFutunModel {

  final case class Info(
      symbol: String,
      interval: IntervalStatus
  ) extends BaseSerializer

}
