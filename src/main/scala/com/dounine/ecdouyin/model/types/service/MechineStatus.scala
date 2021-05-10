package com.dounine.ecdouyin.model.types.service

object MechineStatus extends Enumeration {
  type MechineStatus = Value
  val disconnected: MechineStatus.Value = Value("mechine_disconnected")
  val connected: MechineStatus.Value = Value("mechine_connected")
  val scanned: MechineStatus.Value = Value("mechine_scanned")
  val wechatPage: MechineStatus.Value = Value("mechine_wechatPage")
  val qrcodeChoose: MechineStatus.Value = Value("mechine_qrcodeChoose")
  val qrcodeIdentify: MechineStatus.Value = Value("mechine_qrcodeIdentify")
  val qrcodeUnIdentify: MechineStatus.Value = Value("mechine_qrcodeUnIdentify")
  val paySuccess: MechineStatus.Value = Value("mechine_paySuccess")
  val payFail: MechineStatus.Value = Value("mechine_payFail")
  val timeout: MechineStatus.Value = Value("mechine_timeout")
}
