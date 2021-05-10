package com.dounine.ecdouyin.tools.akka.db

import akka.actor.typed.ActorSystem
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.slf4j.LoggerFactory
import slick.jdbc.JdbcBackend.Database
import slick.util.AsyncExecutor

import java.util.concurrent.TimeUnit

class DBSource(system: ActorSystem[_]) {

  private final val logger = LoggerFactory.getLogger(classOf[DBSource])
  val config = system.settings.config.getConfig("app")
  private val hikariConfig: HikariConfig = {
    val c = new HikariConfig()
    c.setDriverClassName(config.getString("db.driver"))
    c.setJdbcUrl(config.getString("db.url"))
    c.addDataSourceProperty("maxThreads", "30")
    c.setUsername(config.getString("db.username"))
    c.setPassword(config.getString("db.password"))
    c.setMinimumIdle(config.getInt("db.hikaricp.minimumIdle"))
    c.setMaximumPoolSize(config.getInt("db.hikaricp.maximumPoolSize"))
    c.setMaxLifetime(config.getLong("db.hikaricp.maxLifetime"))
    c.setConnectionTimeout(
      TimeUnit.SECONDS.toMillis(config.getInt("db.hikaricp.connectionTimeout"))
    )
    c.setConnectionInitSql(config.getString("db.hikaricp.connectionInitSql"))
    c.setIdleTimeout(
      TimeUnit.SECONDS.toMillis(config.getInt("db.hikaricp.idleTimeout"))
    )
    c.setAutoCommit(true)
    c
  }
  val poolSize: Int = config.getInt("db.hikaricp.maximumPoolSize")
  val dataSource: HikariDataSource = new HikariDataSource(hikariConfig)

  val db = {
    logger.info("HikariDataSource init")
    val asyncExecutor: AsyncExecutor = AsyncExecutor(
      name = "CorePostgresDriver.AsyncExecutor",
      minThreads = poolSize,
      maxThreads = poolSize,
      maxConnections = poolSize,
      queueSize = 100 // the default used by AsyncExecutor.default()
    )
    Database.forDataSource(dataSource, Some(poolSize), asyncExecutor)
  }

}
