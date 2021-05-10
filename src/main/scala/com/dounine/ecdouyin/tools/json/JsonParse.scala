package com.dounine.ecdouyin.tools.json

import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.json4s.Formats
import org.json4s.jackson.Serialization
import org.json4s.native.Serialization.{read, write}

trait JsonParse extends Json4sSupport {
  implicit val serialization: Serialization.type = JsonSuport.serialization
  implicit val formats: Formats = JsonSuport.formats

  implicit class ToJson(data: Any) {
    def toJson: String = {
      write(data)
    }

    def logJson: String = {
      write(
        Map(
          "__" -> data.getClass.getName,
          "data" -> data
        )
      )
    }
  }

  implicit class JsonTo(data: String) {
    def jsonTo[T: Manifest]: T = {
      read[T](data)
    }
  }

}
