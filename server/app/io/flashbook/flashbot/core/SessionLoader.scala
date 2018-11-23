package io.flashbook.flashbot.core

import io.flashbook.flashbot.core.DataSource.Bundle

import scala.concurrent.Future

class DataTypeIndex(val bundles: Map[String, Set[Bundle]]) extends AnyVal {
  def get(dataType: String) = bundles.get(dataType)
  def apply(dataType: String) = get(dataType).get
}

class TopicIndex(val topics: Map[String, DataTypeIndex]) extends AnyVal {
  def get(topic: String) = topics.get(topic)
  def apply(topic: String) = get(topic).get
}

class DataSourceIndex(val sources: Map[String, TopicIndex]) extends AnyVal {
  def get(dataSource: String) = sources.get(dataSource)
  def apply(dataSource: String) = get(dataSource).get
}

class SessionLoader {
  def allDataSources: Future[DataSourceIndex] = ???
}
