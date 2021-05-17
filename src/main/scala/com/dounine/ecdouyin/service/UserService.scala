package com.dounine.ecdouyin.service

import akka.actor.typed.ActorSystem
import com.dounine.ecdouyin.model.models.UserModel
import com.dounine.ecdouyin.store.{EnumMappers, UserTable}
import com.dounine.ecdouyin.tools.akka.db.DataSource
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import scala.concurrent.Future

class UserService(system: ActorSystem[_]) extends EnumMappers {
  private val db = DataSource(system).source().db
  private val dict: TableQuery[UserTable] =
    TableQuery[UserTable]
  private val tableName = dict.baseTableRow.tableName

  def info(apiKey: String): Future[Option[UserModel.DbInfo]] =
    db.run(dict.filter(_.apiKey === apiKey).result.headOption)

  def add(info: UserModel.DbInfo): Future[Int] =
    db.run(dict += info)

  def margin(apiKey: String, margin: BigDecimal): Future[Int] = {
    db.run(dict.filter(_.apiKey === apiKey).result.headOption)
      .flatMap {
        case Some(item) =>
          db.run(
            dict
              .filter(_.apiKey === apiKey)
              .map(i => (i.margin, i.balance))
              .update(item.margin + margin, item.balance - margin)
          )
        case None => Future.failed(new Exception("api not found"))
      }(system.executionContext)
  }

  def unMargin(apiKey: String, margin: BigDecimal): Future[Int] = {
    db.run(dict.filter(_.apiKey === apiKey).result.headOption)
      .flatMap {
        case Some(item) =>
          db.run(
            dict
              .filter(_.apiKey === apiKey)
              .map(i => (i.margin, i.balance))
              .update(item.margin - margin, item.balance + margin)
          )
        case None => Future.failed(new Exception("api not found"))
      }(system.executionContext)
  }

  def releaseMargin(apiKey: String, margin: BigDecimal): Future[Int] = {
    db.run(dict.filter(_.apiKey === apiKey).result.headOption)
      .flatMap {
        case Some(item) =>
          db.run(
            dict
              .filter(_.apiKey === apiKey)
              .map(_.margin)
              .update(item.margin - margin)
          )
        case None => Future.failed(new Exception("api not found"))
      }(system.executionContext)
  }

}
