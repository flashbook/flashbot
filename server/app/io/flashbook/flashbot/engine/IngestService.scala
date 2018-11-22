package io.flashbook.flashbot.engine

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.stream.ActorMaterializer
import io.flashbook.flashbot.core.DataSource
import io.flashbook.flashbot.util.stream.buildMaterializer

class IngestService extends Actor with ActorLogging {
  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = buildMaterializer

  override def receive: Receive = {
    case (name: String, dataDir: String, config: DataSource.DataSourceConfig,
        topicsWhitelist: Set[String], dataTypesWhitelist: Set[String]) =>
      val finalTopics = config.topics.filterKeys(key =>
        topicsWhitelist.isEmpty || topicsWhitelist.contains(key))
      val finalDataTypes = config.data_types.filterKeys(key =>
        dataTypesWhitelist.isEmpty || dataTypesWhitelist.contains(key))
      Class.forName(config.`class`).newInstance.asInstanceOf[DataSource]
        .ingestGroup(dataDir, finalTopics, finalDataTypes)
  }
}
