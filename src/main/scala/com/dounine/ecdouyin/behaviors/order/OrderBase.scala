package com.dounine.ecdouyin.behaviors.order

import akka.Done
import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import com.dounine.ecdouyin.model.models.{BaseSerializer, OrderModel}

import java.time.LocalDateTime

object OrderBase {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("OrderBehavior")

  final case class DataStore(
      mechines: Set[String],
      waitOrders: Map[Long, OrderModel.DbInfo],
      handOrders: Map[Long, OrderModel.DbInfo],
      shutdown: Option[ActorRef[Done]]
  ) extends BaseSerializer

  abstract class State() extends BaseSerializer {
    val data: DataStore
  }

  final case class Shutdowning(data: DataStore) extends State

  final case class Stoped(data: DataStore) extends State

  final case class Idle(data: DataStore) extends State

  final case class Busy(data: DataStore) extends State

  trait Command extends BaseSerializer

  final case class UpdateOk(before: OrderModel.DbInfo, after: OrderModel.DbInfo)
      extends Command

  final case class UpdateFail(
      before: OrderModel.DbInfo,
      after: OrderModel.DbInfo,
      msg: String
  ) extends Command

  final case class Run() extends Command

  final case class RunOk(
      waitPays: Seq[OrderModel.DbInfo]
  ) extends Command

  final case class RunFail(
      msg: String
  ) extends Command

  final case class OrderHand(
      order: OrderModel.DbInfo,
      mechineId: String
  ) extends Command

  final case class Recovery() extends Command

  final case class Shutdown()(val replyTo: ActorRef[Done]) extends Command

  final case class ShutdownComplete()(val replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class ShutdownOk() extends Command

  final case class Cancel(orderId: Long)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Command

  final case class CancelSelfOk(request: Cancel) extends Command

  final case class CancelSelfFail(request: Cancel, msg: String) extends Command

  final case class CancelOk() extends Command

  final case class CancelFail(msg: String) extends Command

  final case class Create(order: OrderModel.DbInfo)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Command

  final case class CreateSelfOk(request: Create, orderId: Long) extends Command

  final case class CreateSelfFail(request: Create, msg: String) extends Command

  final case class CreateOk(orderId: Long) extends Command

  final case class CreateFail(msg: String) extends Command

  final case class Interval() extends Command

  final case class WebPaySuccess(
      order: OrderModel.DbInfo
  ) extends Command

  final case class WebPayFail(
      order: OrderModel.DbInfo,
      msg: String
  ) extends Command

  final val intervalName = "intervalOrder"

}
