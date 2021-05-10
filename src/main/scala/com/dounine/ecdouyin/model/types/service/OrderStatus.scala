package com.dounine.ecdouyin.model.types.service

object OrderStatus extends Enumeration {
  type OrderStatus = Value
  val submitted: OrderStatus.Value = Value("submitted")
  val accepted: OrderStatus.Value = Value("accepted")
  val completed: OrderStatus.Value = Value("completed")
  val canceled: OrderStatus.Value = Value("canceled")
  val margin: OrderStatus.Value = Value("margin")
}
