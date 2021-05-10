package com.dounine.ecdouyin.store

import com.dounine.ecdouyin.model.models.AkkaPersistenerModel
import slick.jdbc.MySQLProfile.api._
import slick.lifted.{PrimaryKey, ProvenShape}

class AkkaPersistenerJournalTable(tag: Tag)
    extends Table[AkkaPersistenerModel.Journal](
      tag,
      _tableName = "ecdouyin_journal"
    ) {

  override def * : ProvenShape[AkkaPersistenerModel.Journal] =
    (
      ordering,
      persistence_id,
      sequence_number,
      deleted,
      tags,
      message
    ).mapTo[AkkaPersistenerModel.Journal]

  def ordering: Rep[Long] =
    column[Long]("ordering", O.Length(20), O.Unique, O.AutoInc)

  def persistence_id: Rep[String] =
    column[String]("persistence_id", O.Length(255))

  def sequence_number: Rep[Long] = column[Long]("sequence_number", O.Length(20))

  def deleted: Rep[Boolean] =
    column[Boolean]("deleted", O.Length(1), O.Default(defaultValue = false))

  def tags: Rep[Option[String]] = column[Option[String]]("tags", O.Length(255))

  def message: Rep[Array[Byte]] = column[Array[Byte]]("message")

  def pk: PrimaryKey =
    primaryKey("ecdouyin_journal_primaryKey", (persistence_id, sequence_number))

}
