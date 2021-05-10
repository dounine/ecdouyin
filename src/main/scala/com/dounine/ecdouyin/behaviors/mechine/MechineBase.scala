package com.dounine.ecdouyin.behaviors.mechine

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel}
import com.dounine.ecdouyin.model.types.service.MechineStatus.MechineStatus
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform

import scala.concurrent.duration.FiniteDuration

object MechineBase {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("MechineBehavior")

  final case class ErrorInfo(
      qrcodeUnIdentify: Option[String] = Option.empty,
      payFail: Option[String] = Option.empty,
      timeout: Option[String] = Option.empty
  ) extends BaseSerializer

  final case class DataStore(
      mechineId: String,
      actor: Option[ActorRef[BaseSerializer]],
      order: Option[OrderModel.DbInfo],
      errors: ErrorInfo,
      orderTimeout: FiniteDuration
  ) extends BaseSerializer

  abstract class State() extends BaseSerializer {
    val data: DataStore
  }

  final case class Disconnected(data: DataStore) extends State

  final case class Connected(data: DataStore) extends State

  final case class WechatPage(data: DataStore) extends State

  final case class Scanned(data: DataStore) extends State

  final case class QrcodeChoose(data: DataStore) extends State

  final case class QrcodeIdentify(data: DataStore) extends State

  final case class QrcodeUnIdentify(data: DataStore) extends State

  final case class PaySuccess(data: DataStore) extends State

  final case class PayFail(data: DataStore) extends State

  final case class Timeout(data: DataStore) extends State

  trait Command extends BaseSerializer

  final case class Shutdown()(val replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class ShutdownOk(request: Shutdown, id: String) extends Command

  final case class SocketConnect(actor: ActorRef[BaseSerializer])
      extends Command

  final case class SocketDisconnect() extends Command

  final case class SocketWechatPage() extends Command

  final case class SocketScanned() extends Command

  final case class SocketQrcodeChoose() extends Command

  final case class SocketQrcodeIdentify() extends Command

  final case class SocketQrcodeUnIdentify(
      screen: String
  ) extends Command

  final case class SocketPaySuccess() extends Command

  final case class SocketPayFail(
      screen: String
  ) extends Command

  final case class SocketTimeout(
      screen: Option[String]
  ) extends Command

  final case class Recovery() extends Command

  final case class CreateOrder(
      order: OrderModel.DbInfo
  )(val replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class CreateOrderOk(request: CreateOrder) extends Command

  final case class CreateOrderFail(request: CreateOrder, msg: String)
      extends Command

  final case class OrderPaySuccess(order: OrderModel.DbInfo) extends Command

  final case class OrderPayFail(order: OrderModel.DbInfo, status: MechineStatus)
      extends Command

  final case class Enable(mechineId: String) extends Command

  final case class Disable(mechineId: String) extends Command

  final case class QrcodeQuery(order: OrderModel.DbInfo) extends Command

  final case class QrcodeQueryOk(request: QrcodeQuery, qrcode: String)
      extends Command

  final val timeoutName = "mechineTimeout"

}
