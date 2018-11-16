package io.flashbook.flashbot.core

import scala.concurrent.Future

trait DataSourceIndex

class FlashbotScope {
  def allDataSources: Future[Map[String, DataSourceIndex]] = ???
}
