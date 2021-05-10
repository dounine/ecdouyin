package com.dounine.ecdouyin.tools.akka.db

import akka.actor.typed.{ActorSystem, Extension, ExtensionId}

class DataSource(system: ActorSystem[_]) extends Extension {

  private val _source = new DBSource(system)

  def source(): DBSource = _source
}

object DataSource extends ExtensionId[DataSource] {

  override def createExtension(system: ActorSystem[_]): DataSource =
    new DataSource(system)

  def get(system: ActorSystem[_]): DataSource = apply(system)
}
