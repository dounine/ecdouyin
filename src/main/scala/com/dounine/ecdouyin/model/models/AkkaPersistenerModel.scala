package com.dounine.ecdouyin.model.models

object AkkaPersistenerModel {

  case class Journal(
      ordering: Long,
      persistence_id: String,
      sequence_number: Long,
      deleted: Boolean,
      tags: Option[String],
      message: Array[Byte]
  ) extends BaseSerializer

  case class Snapshot(
      persistence_id: String,
      sequence_number: Long,
      created: Long,
      snapshot: Array[Byte]
  ) extends BaseSerializer

}
