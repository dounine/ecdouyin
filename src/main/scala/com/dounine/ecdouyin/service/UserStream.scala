package com.dounine.ecdouyin.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.{Flow, Source}
import com.dounine.ecdouyin.model.models.UserModel
import com.dounine.ecdouyin.store.{OrderTable, UserTable}
import com.dounine.ecdouyin.tools.akka.db.DataSource
import slick.jdbc.JdbcBackend

import scala.concurrent.ExecutionContextExecutor

object UserStream {

  def source(
      apiKey: String,
      system: ActorSystem[_]
  ): Source[UserModel.DbInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val userTable = TableQuery[UserTable]

    Slick.source(
      userTable.filter(_.apiKey === apiKey).result
    )
  }

  def queryFlow(
      system: ActorSystem[_]
  ): Flow[String, Either[Throwable, UserModel.DbInfo], NotUsed] = {
    Flow[String]
      .flatMapConcat(source(_: String, system))
      .map(Right.apply)
      .recover {
        case ee => Left(ee)
      }

  }

}
