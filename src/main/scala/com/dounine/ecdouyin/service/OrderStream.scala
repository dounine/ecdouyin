package com.dounine.ecdouyin.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.FlowShape
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, ZipWith}
import com.dounine.ecdouyin.behaviors.engine.CoreEngine
import com.dounine.ecdouyin.model.models.{OrderModel, UserModel}
import com.dounine.ecdouyin.store.{OrderTable, UserTable}
import com.dounine.ecdouyin.tools.akka.db.DataSource

import scala.concurrent.ExecutionContextExecutor

object OrderStream {

  def createOrderToEngine(
      system: ActorSystem[_]
  ): Flow[OrderModel.DbInfo, Either[
    Throwable,
    (OrderModel.DbInfo, UserModel.DbInfo)
  ], NotUsed] = {
    import scala.concurrent.duration._
    val sharding: ClusterSharding = ClusterSharding(system)
    implicit val ec: ExecutionContextExecutor = system.executionContext
    Flow.fromGraph(GraphDSL.create() {
      implicit builder: GraphDSL.Builder[NotUsed] =>
        {
          import GraphDSL.Implicits._
          val broadcast = builder.add(Broadcast[OrderModel.DbInfo](2))
          val zip = builder.add(
            ZipWith(
              (
                  left: Either[Throwable, UserModel.DbInfo],
                  right: Either[Throwable, OrderModel.DbInfo]
              ) => {
                (right match {
                  case e @ Left(value: Throwable) => e
                  case Right(value: OrderModel.DbInfo) =>
                    left.map(i => (value, i))
                }).asInstanceOf[Either[
                  Throwable,
                  (OrderModel.DbInfo, UserModel.DbInfo)
                ]]
              }
            )
          )
          val createFlow: Flow[OrderModel.DbInfo, Either[
            Throwable,
            OrderModel.DbInfo
          ], NotUsed] = Flow[OrderModel.DbInfo]
            .mapAsync(1) {
              order: OrderModel.DbInfo =>
                sharding
                  .entityRefFor(
                    CoreEngine.typeKey,
                    CoreEngine.typeKey.name
                  )
                  .ask(
                    CoreEngine.CreateOrder(
                      order
                    )
                  )(3.seconds)
                  .collect {
                    case CoreEngine.CreateOrderOk(request) => Right(order)
                    case CoreEngine.CreateOrderFail(request, msg) =>
                      Left(new Exception(msg))
                  }
                  .recover {
                    case e => Left(e)
                  }
            }
          val queryUserInfoFlowShape = builder.add(UserStream.queryFlow(system))
          val mapFlow = builder.add(Flow[OrderModel.DbInfo].map(_.apiKey))
          val createFlowShape = builder.add(createFlow)

          broadcast.out(0) ~> mapFlow ~> queryUserInfoFlowShape ~> zip.in0
          broadcast.out(1) ~> createFlowShape ~> zip.in1

          FlowShape(broadcast.in, zip.out)
        }
    })

  }

  def createOrderMarginUser(
      system: ActorSystem[_]
  ): Flow[OrderModel.DbInfo, Either[
    Throwable,
    (OrderModel.DbInfo, UserModel.DbInfo)
  ], NotUsed] = {
    val db = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val orderTable = TableQuery[OrderTable]
    val userTable = TableQuery[UserTable]
    val insertAndGetId: slickSession.profile.IntoInsertActionComposer[
      OrderModel.DbInfo,
      OrderModel.DbInfo
    ] =
      orderTable returning orderTable.map(_.orderId) into ((item, orderId) =>
        item.copy(orderId = orderId)
      )
    Slick
      .flowWithPassThrough((order: OrderModel.DbInfo) => {
        (for {
          dbOrder <- insertAndGetId += order
          dbUserInfo <-
            userTable
              .filter(item =>
                item.apiKey === dbOrder.apiKey && item.margin > dbOrder.margin
              )
              .result
              .head
          _ <-
            userTable
              .filter(_.apiKey === dbUserInfo.apiKey)
              .map(i => (i.margin, i.balance))
              .update(
                (
                  dbUserInfo.margin + order.margin,
                  dbUserInfo.balance - order.margin
                )
              )
          updateUserInfo <-
            userTable
              .filter(item => item.apiKey === order.apiKey)
              .result
              .head
        } yield (dbOrder, updateUserInfo)).transactionally
      })
      .map(Right.apply)
      .recover {
        case ee => {
          ee.printStackTrace()
          Left(ee)
        }
      }
  }

}
