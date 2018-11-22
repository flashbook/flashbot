package io.flashbook.flashbot.engine

import akka.actor.{Actor, ActorLogging, Props}
import io.flashbook.flashbot.core.DataSource.DataSourceConfig

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * A DataServer runs and manages a set of data sources.
  */
class DataServer(configs: Map[String, DataSourceConfig]) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = context.system.dispatcher
  context.system.scheduler.schedule(1 second, 1 second, self, "tick")

  val dataSourceActors = configs.map {
    case (key, config) =>
      context.system.actorOf(Props(new DataSourceActor(config)), key)
  }

  override def receive = {
    case a =>
      log.info(a.toString)
//      log.info(members.values.map(m => s"${m.address} (${m.uniqueAddress})").mkString(", "))
  }
}
