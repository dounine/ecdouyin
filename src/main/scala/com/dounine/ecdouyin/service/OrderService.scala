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
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.model.types.service.PayStatus
import com.dounine.ecdouyin.model.types.service.PayStatus.PayStatus
import com.dounine.ecdouyin.store.{EnumMappers, OrderTable, UserTable}
import com.dounine.ecdouyin.tools.akka.ConnectSettings
import com.dounine.ecdouyin.tools.akka.db.DataSource
import com.dounine.ecdouyin.tools.util.MD5Util
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import scala.concurrent.Future
import scala.concurrent.duration._
class OrderService(system: ActorSystem[_]) extends EnumMappers {
  private val db = DataSource(system).source().db
  private val dict: TableQuery[OrderTable] =
    TableQuery[OrderTable]

  private val userDict = TableQuery[UserTable]

  implicit val ec = system.executionContext
  implicit val materializer = SystemMaterializer(system).materializer
  private val logger = LoggerFactory.getLogger(classOf[OrderService])
  private val http = Http(system)

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

  def addOrderAndMarginUser(info: OrderModel.DbInfo): Future[Long] = {
    db.run((for {
      orderId <- insertAndGetId += info
      userInfo <- userDict.filter(_.apiKey === info.apiKey).result.head
      _: Int <-
        userDict
          .filter(_.apiKey === info.apiKey)
          .map(i => (i.margin, i.balance))
          .update(
            (userInfo.margin + info.margin, userInfo.balance - info.margin)
          )
    } yield orderId).transactionally)
  }

  def unMarginOrderAppendToUser(
      info: OrderModel.DbInfo,
      margin: BigDecimal
  ): Future[Int] = {
    db.run((for {
      _ <-
        dict
          .filter(_.orderId === info.orderId)
          .map(item =>
            (
              item.margin,
              item.status,
              item.payCount
            )
          )
          .update((info.margin, info.status, info.payCount))
      userInfo <- userDict.filter(_.apiKey === info.apiKey).result.head
      updateUser <-
        userDict
          .filter(_.apiKey === info.apiKey)
          .map(i => (i.margin, i.balance))
          .update(userInfo.margin - margin, userInfo.balance + margin)
    } yield updateUser).transactionally)
  }

  def releaseMarginOrderToUser(
      info: OrderModel.DbInfo,
      margin: BigDecimal
  ): Future[Int] = {
    db.run((for {
      _ <-
        dict
          .filter(_.orderId === info.orderId)
          .map(item =>
            (
              item.margin,
              item.status,
              item.payCount
            )
          )
          .update((info.margin, info.status, info.payCount))
      userInfo <- userDict.filter(_.apiKey === info.apiKey).result.head
      updateUser <-
        userDict
          .filter(_.apiKey === info.apiKey)
          .map(_.margin)
          .update(userInfo.margin - margin)
    } yield updateUser).transactionally)
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

  def updateAll(info: OrderModel.DbInfo): Future[Int] =
    db.run(
      dict
        .filter(_.orderId === info.orderId)
        .map(item =>
          (
            item.margin,
            item.status,
            item.payCount
          )
        )
        .update((info.margin, info.status, info.payCount))
    )

  def userInfo(
      platform: PayPlatform,
      userId: String
  ): Future[Option[OrderModel.UserInfo]] = {
    http
      .singleRequest(
        request = HttpRequest(
          uri =
            s"https://webcast.amemv.com/webcast/user/open_info/?search_ids=${userId}&aid=1128&source=1a0deeb4c56147d0f844d473b325a28b&fp=verify_khq5h2bx_oY8iEaW1_b0Yt_4Hvt_9PRa_3U70XFUYPgzI&t=${System
              .currentTimeMillis()}",
          method = HttpMethods.GET
        ),
        settings = ConnectSettings.httpSettings(system)
      )
      .flatMap {
        case HttpResponse(_, _, entity, _) =>
          entity.dataBytes
            .runFold(ByteString.empty)(_ ++ _)
            .map(_.utf8String)
            .map(_.jsonTo[OrderModel.DouYinSearchResponse])
            .map(item => {
              if (item.data.open_info.nonEmpty) {
                val data: OrderModel.DouYinSearchOpenInfo =
                  item.data.open_info.head
                Option(
                  OrderModel.UserInfo(
                    nickName = data.nick_name,
                    id = data.search_id,
                    avatar = data.avatar_thumb.url_list.head
                  )
                )
              } else {
                Option.empty
              }
            })
        case msg @ _ =>
          logger.error(s"请求失败 $msg")
          Future.failed(new Exception(s"请求失败 $msg"))
      }
  }

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
                        money = order.money,
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
              .recover {
                case e => Future.failed(new Exception(e.getMessage))
              }
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
