package com.dounine.ecdouyin.service

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes
}
import akka.stream.{RestartSettings, SystemMaterializer}
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import akka.util.ByteString
import com.dounine.ecdouyin.model.models.{OrderModel, UserModel}
import com.dounine.ecdouyin.model.types.service.MechinePayStatus.MechinePayStatus
import com.dounine.ecdouyin.model.types.service.PayStatus
import com.dounine.ecdouyin.model.types.service.PayStatus.PayStatus
import com.dounine.ecdouyin.store.{EnumMappers, OrderTable, UserTable}
import com.dounine.ecdouyin.tools.akka.ConnectSettings
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.dounine.ecdouyin.tools.util.MD5Util
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import scala.concurrent.Future
import scala.concurrent.duration._
class OrderService(system: ActorSystem[_]) extends EnumMappers {
  private val db = DataSource(system).source().db
  private val dict: TableQuery[OrderTable] =
    TableQuery[OrderTable]

  implicit val ec = system.executionContext
  implicit val materializer = SystemMaterializer(system).materializer

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

  val http = Http(system)
  def callback(
      order: OrderModel.DbInfo,
      status: PayStatus,
      callback: String,
      apiSecret: String,
      msg: Option[String]
  ): Future[String] = {
    RestartSource
      .withBackoff(
        RestartSettings(
          minBackoff = 1.seconds, //最小重启间隔
          maxBackoff = 10.seconds, //最大重启间隔
          randomFactor = 0.2
        ).withMaxRestarts(
          3,
          1.seconds
        ) //count最多少重启多少次(总共运行是count+1次)、within 测试发现没有用
      )(() => {
        Source
          .future(
            http
              .singleRequest(
                request = HttpRequest(
                  uri = callback,
                  method = HttpMethods.POST,
                  entity = HttpEntity(
                    contentType = MediaTypes.`application/json`,
                    string = OrderModel
                      .CallbackInfo(
                        apiKey = order.apiKey,
                        orderId = order.orderId.toString,
                        outOrder = order.outOrder,
                        money = order.money.toString,
                        account = order.account,
                        platform = order.platform,
                        status = status,
                        sign = MD5Util.md5(
                          apiSecret + order.orderId.toString + order.outOrder + order.money.toString + order.account + order.platform + status
                        ),
                        msg
                      )
                      .toJson
                  )
                ),
                settings = ConnectSettings.httpSettings(system)
              )
              .flatMap {
                case HttpResponse(_, _, entity, _) =>
                  entity.dataBytes
                    .runFold(ByteString.empty)(_ ++ _)
                    .map(_.utf8String)
                case msg @ _ =>
                  Future.failed(new Exception(s"请求失败 $msg"))
              }
          )
      })
      .runWith(Sink.head)
  }

}
