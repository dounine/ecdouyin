package com.dounine.ecdouyin.tools.akka

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.settings.{
  ClientConnectionSettings,
  ConnectionPoolSettings
}
import com.typesafe.config.{Config, ConfigFactory}

import java.net.InetSocketAddress
import java.time
import scala.concurrent.duration._

object ConnectSettings {

  private val config: Config = ConfigFactory.load().getConfig("app")

  def settings(system: ActorSystem[_]): ClientConnectionSettings = {
    val enableProxy: Boolean = config.getBoolean("proxy.enable")
    val proxyHost: String = config.getString("proxy.host")
    val proxyPort: Int = config.getInt("proxy.port")
    val timeout: time.Duration = config.getDuration("proxy.timeout")

    val httpsProxyTransport: ClientTransport = ClientTransport
      .httpsProxy(InetSocketAddress.createUnresolved(proxyHost, proxyPort))

    val conn: ClientConnectionSettings = ClientConnectionSettings(system)
      .withConnectingTimeout(timeout.getSeconds.seconds)
    if (enableProxy) {
      conn.withTransport(httpsProxyTransport)
    } else conn
  }

  def httpSettings(system: ActorSystem[_]): ConnectionPoolSettings = {
    ConnectionPoolSettings(system).withConnectionSettings(settings(system))
  }

}
