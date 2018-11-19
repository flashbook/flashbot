package io.flashbook.flashbot.core

import scala.concurrent.Future

trait DataSourceIndex

class SessionLoader {
  def allDataSources: Future[Map[String, DataSourceIndex]] = ???
}
