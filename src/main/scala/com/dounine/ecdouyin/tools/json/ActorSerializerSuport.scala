package com.dounine.ecdouyin.tools.json

import org.json4s.Formats

trait ActorSerializerSuport extends JsonParse {

  override implicit val formats: Formats =
    JsonSuport.formats + JsonSuport.ActorRefSerializer
}
