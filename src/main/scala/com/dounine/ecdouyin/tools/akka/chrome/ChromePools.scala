package com.dounine.ecdouyin.tools.akka.chrome

import akka.actor.typed.{ActorSystem, Extension, ExtensionId}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.commons.pool2.impl.{
  GenericKeyedObjectPool,
  GenericKeyedObjectPoolConfig,
  GenericObjectPool,
  GenericObjectPoolConfig
}

class ChromePools(system: ActorSystem[_]) extends Extension {

  private final val config: Config = system.settings.config.getConfig("app")
  private val poolConfig: GenericObjectPoolConfig[Chrome] =
    new GenericObjectPoolConfig()
  poolConfig.setMinIdle(config.getInt("selenium.pool.minIdle"))
  poolConfig.setMaxIdle(config.getInt("selenium.pool.maxIdle"))
  poolConfig.setMaxTotal(config.getInt("selenium.pool.maxTotal"))
  poolConfig.setMaxWaitMillis(config.getInt("selenium.pool.maxWaitMillis"))
  poolConfig.setTestOnBorrow(config.getBoolean("selenium.pool.testOnBorrow"))
  poolConfig.setMinEvictableIdleTimeMillis(
    config.getInt("selenium.pool.minEvictableIdleTimeMillis")
  )
  poolConfig.setTimeBetweenEvictionRunsMillis(
    config.getInt("selenium.pool.timeBetweenEvictionRunsMillis")
  )
  poolConfig.setBlockWhenExhausted(
    config.getBoolean("selenium.pool.blockWhenExhausted")
  )

  private val _pools =
    new GenericObjectPool[Chrome](new ChromeFactory(system), poolConfig)

  val pools: GenericObjectPool[Chrome] = _pools

}

object ChromePools extends ExtensionId[ChromePools] {

  override def createExtension(system: ActorSystem[_]): ChromePools =
    new ChromePools(system)

  def get(system: ActorSystem[_]): ChromePools = apply(system)
}
