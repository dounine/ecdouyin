package com.dounine.ecdouyin.router.routers

import akka.actor.typed.ActorSystem
import akka.cluster.{Cluster, MemberStatus}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._

import scala.concurrent.duration._

class HealthRouter(system: ActorSystem[_]) extends SuportRouter {
  val cluster: Cluster = Cluster.get(system)

  val route = get {
    path("ready") {
      withRequestTimeout(1.seconds, request => timeoutResponse) {
        ok
      }
    } ~ path("alive") {
      if (
        cluster.selfMember.status == MemberStatus.Up || cluster.selfMember.status == MemberStatus.WeaklyUp
      ) {
        ok
      } else {
        complete(StatusCodes.NotFound)
      }
    }
  }
}
