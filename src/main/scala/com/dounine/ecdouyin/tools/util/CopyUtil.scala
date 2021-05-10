package com.dounine.ecdouyin.tools.util

import java.lang.reflect.{Constructor, Method, Modifier}

object CopyUtil {

  def main(args: Array[String]): Unit = {
    val hello = ""

    println(hello)
  }

  def copy[T](o: T)(values: Map[String, Any]): T = {
    if (values.isEmpty) return o
    new Copier(o.getClass)(o, values)
  }

  private class Copier(cls: Class[_]) {
    private val ctor: Constructor[_] = cls.getConstructors.apply(0)
    private val getters: Array[Method] = cls.getDeclaredFields
      .filter { f =>
        val m: Int = f.getModifiers
        Modifier.isPrivate(m) && Modifier.isFinal(m) && !Modifier.isStatic(m)
      }
      .take(ctor.getParameterTypes.length)
      .map(f => cls.getMethod(f.getName))

    def apply[T](o: T, values: Map[String, Any]): T = {
      val byIx: Map[Int, Object] = values.map { case (name, value) =>
        val ix: Int = getters.indexWhere(_.getName == name)
        if (ix < 0) throw new IllegalArgumentException("Unknown field: " + name)
        (ix, value.asInstanceOf[Object])
      }

      val args: IndexedSeq[Object] = getters.indices.map { i =>
        byIx.getOrElse(i, getters(i).invoke(o))
      }
      ctor.newInstance(args: _*).asInstanceOf[T]
    }

  }

}
