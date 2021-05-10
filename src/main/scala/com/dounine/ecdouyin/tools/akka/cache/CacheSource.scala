package com.dounine.ecdouyin.tools.akka.cache

import akka.actor.typed.{ActorSystem, Extension, ExtensionId}

class CacheSource(system: ActorSystem[_]) extends Extension {

  private val _cache = new Cache(system)

  def cache(): Cache = _cache
}
object CacheSource extends ExtensionId[CacheSource] {

  override def createExtension(system: ActorSystem[_]): CacheSource = new CacheSource(system)

  def get(system: ActorSystem[_]): CacheSource = apply(system)

}
