package com.dounine.ecdouyin.store

import com.dounine.ecdouyin.model.models.AkkaPersistenerModel
import slick.jdbc.MySQLProfile.api._
import slick.lifted.{PrimaryKey, ProvenShape}

class AkkaPersistenerSnapshotTable(tag: Tag)
    extends Table[AkkaPersistenerModel.Snapshot](
      tag,
      _tableName = "ecdouyin_snapshot"
    ) {

  override def * : ProvenShape[AkkaPersistenerModel.Snapshot] =
    (
      persistence_id,
      sequence_number,
      created,
      snapshot
    ).mapTo[AkkaPersistenerModel.Snapshot]

  def persistence_id: Rep[String] =
    column[String]("persistence_id", O.Length(255))

  def sequence_number: Rep[Long] = column[Long]("sequence_number", O.Length(20))

  def created: Rep[Long] = column[Long]("created", O.Length(20))

  def snapshot: Rep[Array[Byte]] = column[Array[Byte]]("snapshot")

  def pk: PrimaryKey =
    primaryKey("ecdouyin_snapshot_primaryKey", (persistence_id, sequence_number))

}
