package com.dounine.ecdouyin.model.types.service

object MechinePayStatus extends Enumeration {
  type MechinePayStatus = Value
  val dbLength: Int = 20
  val normal: MechinePayStatus.Value = Value("mp_normal")
  val payed: MechinePayStatus.Value = Value("mp_payed")
  val payerr: MechinePayStatus.Value = Value("mp_payerr")
}
