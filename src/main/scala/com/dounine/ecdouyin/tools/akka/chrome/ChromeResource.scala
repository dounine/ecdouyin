package com.dounine.ecdouyin.tools.akka.chrome

import akka.actor.typed.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.dounine.ecdouyin.service.DictionaryService
import com.dounine.ecdouyin.tools.json.JsonParse
import com.dounine.ecdouyin.tools.util.ServiceSingleton
import com.typesafe.config.Config
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.remote.html5.RemoteWebStorage
import org.openqa.selenium.remote.{RemoteExecuteMethod, RemoteWebDriver}
import org.openqa.selenium.{Cookie, Dimension}
import org.slf4j.LoggerFactory

import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContextExecutor, Future}

class ChromeResource(system: ActorSystem[_]) extends JsonParse {

  private final val config: Config = system.settings.config.getConfig("app")

  val createTime = LocalDateTime.now()
  private val logger = LoggerFactory.getLogger(classOf[ChromeResource])
  implicit val materialize: Materializer = SystemMaterializer(
    system
  ).materializer
  val chromeOptions: ChromeOptions = new ChromeOptions()
  chromeOptions.setHeadless(config.getBoolean("selenium.headless"))
  chromeOptions.addArguments("--no-startup-window")
  val hubUrl: String = config.getString("selenium.remoteUrl")
  val webDriver = new RemoteWebDriver(new URL(hubUrl), chromeOptions)
  this.webDriver
    .manage()
    .timeouts()
    .implicitlyWait(config.getInt("selenium.implicitlyWait"), TimeUnit.SECONDS)
  this.webDriver
    .manage()
    .window()
    .setSize(
      new Dimension(
        config.getInt("selenium.size.width"),
        config.getInt("selenium.size.height")
      )
    )
  val dictionaryService = ServiceSingleton.get(classOf[DictionaryService])

  def driver(cookieName: String): Future[RemoteWebDriver] = {
    dictionaryService
      .info(cookieName)
      .map {
        case Some(cookie) => {
          val maps: Map[String, String] =
            cookie.text.jsonTo[Map[String, String]]
          val executeMethod = new RemoteExecuteMethod(this.webDriver)
          val webStorage = new RemoteWebStorage(executeMethod)
          maps
            .filter(_._1.startsWith("localStorage|"))
            .foreach(item => {
              webStorage.getLocalStorage
                .setItem(item._1.substring("localStorage|".length), item._2)
            })
          maps
            .filter(_._1.startsWith("cookie|"))
            .foreach(item => {
              this.webDriver
                .manage()
                .addCookie(
                  new Cookie(item._1.substring("cookie|".length), item._2)
                )
            })
          this.webDriver.get(
            "https://www.douyin.com/falcon/webcast_openpc/pages/douyin_recharge/index.html?is_new_connect=0&is_new_user=0"
          )
          this.webDriver.navigate().refresh()
          this.webDriver
        }
        case None => {
          logger.warn(s"${cookieName} not found")
          this.webDriver
        }
      }(system.executionContext)
  }
  def driver(): RemoteWebDriver = this.webDriver

}
