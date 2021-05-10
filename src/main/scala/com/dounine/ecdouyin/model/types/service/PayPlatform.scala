package com.dounine.ecdouyin.model.types.service

object PayPlatform extends Enumeration {
  type PayPlatform = Value
  val dbLength: Int = 8
  val douyin: PayPlatform.Value = Value("douyin")
  val kuaishou: PayPlatform.Value = Value("kuaishou")

  val list: Seq[PayPlatform.Value] = Seq(douyin, kuaishou)
}
