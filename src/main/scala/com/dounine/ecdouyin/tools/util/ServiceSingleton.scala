package com.dounine.ecdouyin.tools.util

import akka.actor.typed.ActorSystem
import org.slf4j.LoggerFactory

import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap


object ServiceSingleton {

  private val map: ConcurrentHashMap[Any, Any] =
    new ConcurrentHashMap[Any, Any]()
  private val logger =
    LoggerFactory.getLogger(ServiceSingleton.getClass)

  def instance[T](clazz: Class[T], system: ActorSystem[_]): T = {
    if (!map.containsKey(clazz)) {
      logger.debug("{} instance", clazz)
      val cls: Constructor[_] = clazz.getConstructors.apply(0)
      val obj: Any = cls.newInstance(Seq(system): _*)
      map.put(clazz, obj)
    }
    map.get(clazz).asInstanceOf[T]
  }

  def get[T](clazz: Class[T]): T = {
    map.get(clazz).asInstanceOf[T]
  }

  def put[T](clazz: Class[T], value: Any): Unit = {
    map.put(clazz, value)
  }


}
