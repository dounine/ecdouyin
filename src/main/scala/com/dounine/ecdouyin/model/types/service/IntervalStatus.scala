package com.dounine.ecdouyin.model.types.service

object IntervalStatus extends Enumeration {
  type IntervalStatus = Value
  val min1: IntervalStatus.Value = Value("1min")
  val min5: IntervalStatus.Value = Value("5min")
  val min15: IntervalStatus.Value = Value("15min")
  val min30: IntervalStatus.Value = Value("30min")
  val min45: IntervalStatus.Value = Value("45min")
  val hour1: IntervalStatus.Value = Value("1h")
  val hour2: IntervalStatus.Value = Value("2h")
  val hour4: IntervalStatus.Value = Value("4h")
  val day1: IntervalStatus.Value = Value("1day")
  val week1: IntervalStatus.Value = Value("1week")
  val month1: IntervalStatus.Value = Value("1month")
}
