package com.dounine.ecdouyin.service

import akka.actor.typed.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.dounine.ecdouyin.model.models.DictionaryModel
import com.dounine.ecdouyin.model.types.service.PayPlatform.PayPlatform
import com.dounine.ecdouyin.store.{DictionaryTable, EnumMappers}
import com.dounine.ecdouyin.tools.akka.db.DataSource
import io.circe.syntax.KeyOps
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.MySQLProfile
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

class DictionaryService(system: ActorSystem[_]) extends EnumMappers {

  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val ec: ExecutionContextExecutor = materializer.executionContext
  private val db = DataSource(system).source().db
  private val dict = TableQuery[DictionaryTable]

  def upsert(key: String, value: String): Future[Int] =
    db.run(
      dict.insertOrUpdate(
        DictionaryModel.DbInfo(
          key = key,
          text = value,
          createTime = LocalDateTime.now()
        )
      )
    )

  def info(
      key: String
  ): Future[Option[DictionaryModel.DbInfo]] =
    db.run(dict.filter(_.key === key).result.headOption)
}
