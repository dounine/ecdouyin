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
  //borrowObject	从池中借出一个对象。要么调用PooledObjectFactory.makeObject方法创建，要么对一个空闲对象使用PooledObjectFactory.activeObject进行激活，然后使用PooledObjectFactory.validateObject方法进行验证后再返回
  //returnObject	将一个对象返还给池。根据约定：对象必须 是使用borrowObject方法从池中借出的
  //invalidateObject	废弃一个对象。根据约定：对象必须 是使用borrowObject方法从池中借出的。通常在对象发生了异常或其他问题时使用此方法废弃它
  //addObject	使用工厂创建一个对象，钝化并且将它放入空闲对象池
  //getNumberIdle	返回池中空闲的对象数量。有可能是池中可供借出对象的近似值。如果这个信息无效，返回一个负数
  //getNumActive	返回从借出的对象数量。如果这个信息不可用，返回一个负数
  //clear	清除池中的所有空闲对象，释放其关联的资源（可选）。清除空闲对象必须使用PooledObjectFactory.destroyObject方法
  //close	关闭池并释放关联的资源

//用于生成一个新的ObjectPool实例
//  override def makeObject(): PooledObject[ChromeResource] = {
//
//  }

  //每一个钝化（passivated）的ObjectPool实例从池中借出（borrowed）前调用
  override def activateObject(p: PooledObject[ChromeResource]): Unit = {
    p.getObject.refresh()
  }

  override def wrap(t: ChromeResource): PooledObject[ChromeResource] =
    new DefaultPooledObject[ChromeResource](t)

  //当ObjectPool实例返还池中的时候调用
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

  //当ObjectPool实例从池中被清理出去丢弃的时候调用（是否根据validateObject的测试结果由具体的实现在而定）
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

  //可能用于从池中借出对象时，对处于激活（activated）状态的ObjectPool实例进行测试确保它是有效的。也有可能在ObjectPool实例返还池中进行钝化前调用进行测试是否有效。它只对处于激活状态的实例调用
  override def validateObject(p: PooledObject[ChromeResource]): Boolean = true
}
