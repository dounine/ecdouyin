package com.dounine.ecdouyin.tools.akka.chrome

import akka.actor.typed.ActorSystem
import org.apache.commons.pool2.impl.DefaultPooledObject
import org.apache.commons.pool2.{BasePooledObjectFactory, PooledObject}
import org.slf4j.{Logger, LoggerFactory}
import scala.jdk.CollectionConverters._
class ChromeFactory(system: ActorSystem[_])
    extends BasePooledObjectFactory[ChromeResource] {

  private val logger: Logger = LoggerFactory.getLogger(classOf[ChromeFactory])

  override def create(): ChromeResource = {
    new ChromeResource(system)
  }

  override def activateObject(p: PooledObject[ChromeResource]): Unit = {
    p.getObject
      .driver()
      .get(
        "https://www.douyin.com/falcon/webcast_openpc/pages/douyin_recharge/index.html?is_new_connect=0&is_new_user=0"
      )
  }

  override def wrap(t: ChromeResource): PooledObject[ChromeResource] =
    new DefaultPooledObject[ChromeResource](t)

  override def passivateObject(p: PooledObject[ChromeResource]): Unit = {
    val resource = p.getObject
    val driver = resource.driver()
    val originHandler = driver.getWindowHandle
    driver.getWindowHandles.asScala
      .filterNot(_ == originHandler)
      .foreach(window => {
        driver.switchTo().window(window)
        driver.close()
      })
    driver.get(
      "https://www.douyin.com/falcon/webcast_openpc/pages/douyin_recharge/index.html?is_new_connect=0&is_new_user=0"
    )
  }

  override def destroyObject(p: PooledObject[ChromeResource]): Unit = {
    super.destroyObject(p)
    try {
      if (p.getObject.driver() != null) {
        val resource = p.getObject
        resource.driver().quit()
      }
    } catch {
      case e: Exception =>
    }
  }

  override def validateObject(p: PooledObject[ChromeResource]): Boolean = true
}
