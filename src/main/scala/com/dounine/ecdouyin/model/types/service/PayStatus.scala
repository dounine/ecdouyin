package com.dounine.ecdouyin.model.types.service

object PayStatus extends Enumeration {
  type PayStatus = Value
  val dbLength: Int = 8
  val normal: PayStatus.Value = Value("normal")
  val payed: PayStatus.Value = Value("payed")
  val payerr: PayStatus.Value = Value("payerr")
  val cancel: PayStatus.Value = Value("cancel")

  val list: Seq[PayStatus.Value] = Seq(normal, payed, payerr, cancel)
}
