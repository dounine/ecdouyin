package com.dounine.ecdouyin.router.routers

import akka.actor.typed.ActorSystem
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.CachingDirectives._
import akka.http.scaladsl.server.{RequestContext, RouteResult}

import scala.concurrent.duration._

class CachingRouter(system: ActorSystem[_]) extends SuportRouter {

  val keyFunction: PartialFunction[RequestContext, Uri] = {
    case r: RequestContext => r.request.uri
  }

  val defaultCachingSettings = CachingSettings(system)
  val lfuCacheSettings = defaultCachingSettings
    .lfuCacheSettings
    .withInitialCapacity(20)
    .withMaxCapacity(50)
    .withTimeToLive(20.seconds)
    .withTimeToIdle(10.seconds)

  val cachingSettings = defaultCachingSettings.withLfuCacheSettings(lfuCacheSettings)
  val lfuCache: Cache[Uri, RouteResult] = LfuCache(cachingSettings)

  var i = 0
  val route = get {
    path("cached") {
      cache(lfuCache, keyFunction) {
        complete {
          i += 1
          i.toString
        }
      }
    }
  }

}
