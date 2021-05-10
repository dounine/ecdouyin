package com.dounine.ecdouyin.store

import com.dounine.ecdouyin.model.models.DictionaryModel
import slick.jdbc.MySQLProfile.api._
import slick.lifted.{PrimaryKey, ProvenShape}

import java.time.LocalDateTime

class DictionaryTable(tag: Tag)
    extends Table[DictionaryModel.DbInfo](
      tag,
      _tableName = "ecdouyin_dictionary"
    )
    with EnumMappers {

  override def * : ProvenShape[DictionaryModel.DbInfo] =
    (
      key,
      text,
      createTime
    ).mapTo[DictionaryModel.DbInfo]

  def key: Rep[String] = column[String]("key", O.PrimaryKey, O.Length(30))

  def text: Rep[String] = column[String]("text", O.SqlType("text"))

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime]("createTime", O.Length(23))(localDateTime2timestamp)

}
