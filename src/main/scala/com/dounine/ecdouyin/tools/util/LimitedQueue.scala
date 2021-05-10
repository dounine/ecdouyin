package com.dounine.ecdouyin.tools.util

import scala.collection.mutable

class LimitedQueue[A](maxSize: Int) extends mutable.Queue[A] {
  def add(elem: A): this.type = {
    if (length >= maxSize) dequeue()
    append(elem)
    this
  }
}
