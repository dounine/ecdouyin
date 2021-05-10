package com.dounine.ecdouyin.model.types.service

object AppPage extends Enumeration {
  type AppPage = Value
  val jumpedWechatPage: AppPage.Value = Value("jumped_wechat_page")
  val jumpedScannedPage: AppPage.Value = Value("jumped_scanned_page")
  val jumpedQrcodeChoosePage: AppPage.Value = Value("jumped_qrcode_choose_page")
  val jumpedQrcodeIdentifyPage: AppPage.Value = Value(
    "jumped_qrcode_identify_page"
  )
  val jumpedQrcodeUnIdentifyPage: AppPage.Value = Value(
    "jumped_qrcode_un_identify_page"
  )
  val jumpedPaySuccessPage: AppPage.Value = Value("jumped_pay_success_page")
  val jumpedPayFailPage: AppPage.Value = Value("jumped_pay_fail_page")
  val jumpedTimeoutPage: AppPage.Value = Value("jumped_timeout_page")
}
