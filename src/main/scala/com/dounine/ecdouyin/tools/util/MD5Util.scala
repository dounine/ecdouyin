package com.dounine.ecdouyin.tools.util

import org.apache.commons.codec.binary.Hex

import java.security.{MessageDigest, NoSuchAlgorithmException}
import java.util.concurrent.{Executors, TimeUnit}
import java.util.zip.CRC32

object MD5Util {
  private def md5(key: Array[Byte]): String = {
    md5(key, 0, key.length)
  }

  private def md5(key: Array[Byte], offset: Int, length: Int): String = {
    try {
      val e: MessageDigest = MessageDigest.getInstance("MD5")
      e.update(key, offset, length)
      val digest: Array[Byte] = e.digest
      new String(Hex.encodeHex(digest))
    } catch {
      case var5: NoSuchAlgorithmException => {
        throw new RuntimeException("Error computing MD5 hash", var5)
      }
    }
  }

  def md5(str: String): String = {
    md5(str.getBytes)
  }

  private def md5(str: String, offset: Int, length: Int): String = {
    md5(str.getBytes, offset, length)
  }

  def getCrc(str: String, tableCount: Int): Int = {
    val crc32 = new CRC32
    crc32.update(str.getBytes)
    ((crc32.getValue % tableCount) + 1).toInt
  }

  def getCrcValue(str: String): Long = {
    val crc32 = new CRC32
    crc32.update(str.getBytes)
    crc32.getValue
  }
}
