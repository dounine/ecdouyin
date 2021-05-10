package com.dounine.ecdouyin.service

import akka.actor.typed.ActorSystem
import com.dounine.ecdouyin.model.models.{OrderModel, UserModel}
import com.dounine.ecdouyin.model.types.service.MechinePayStatus.MechinePayStatus
import com.dounine.ecdouyin.model.types.service.PayStatus
import com.dounine.ecdouyin.model.types.service.PayStatus.PayStatus
import com.dounine.ecdouyin.store.{EnumMappers, OrderTable, UserTable}
import com.dounine.ecdouyin.tools.akka.db.DataSource
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import scala.concurrent.Future

class OrderService(system: ActorSystem[_]) extends EnumMappers {
  private val db = DataSource(system).source().db
  private val dict: TableQuery[OrderTable] =
    TableQuery[OrderTable]

  def infoOrder(orderId: Long): Future[Option[OrderModel.DbInfo]] =
    db.run(dict.filter(_.orderId === orderId).result.headOption)

  def infoOutOrder(outOrder: String): Future[Option[OrderModel.DbInfo]] =
    db.run(dict.filter(_.outOrder === outOrder).result.headOption)

  val insertAndGetId =
    dict returning dict.map(_.orderId) into ((item, id) => id)

  def add(info: OrderModel.DbInfo): Future[Long] = {
    db.run(
      insertAndGetId += info
    )
  }

  def cancelOrder(orderId: Long): Future[Int] = {
    db.run(
      dict
        .filter(_.orderId === orderId)
        .map(i => (i.status, i.margin))
        .update(PayStatus.cancel, BigDecimal("0.00"))
    )
  }

  def cancelOutOrder(outOrder: String): Future[Int] = {
    db.run(
      dict
        .filter(_.outOrder === outOrder)
        .map(i => (i.status, i.margin))
        .update(PayStatus.cancel, BigDecimal("0.00"))
    )
  }

  def all(status: PayStatus): Future[Seq[OrderModel.DbInfo]] =
    db.run(
      dict.filter(i => i.status === status && i.payCount < 3).result
    )

  def update(info: OrderModel.DbInfo): Future[Int] =
    db.run(
      dict.insertOrUpdate(info)
    )

  def updateMechineStatus(
      orderId: Long,
      mechineStatus: MechinePayStatus
  ): Future[Int] =
    db.run(
      dict
        .filter(_.orderId === orderId)
        .map(_.mechineStatus)
        .update(mechineStatus)
    )

}
