package io.flashbook.flashbot.data

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import io.flashbook.flashbot.core.{DataSource, Utils}

class IngestService extends Actor with ActorLogging {
  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = Utils.buildMaterializer

  override def receive: Receive = {
    case (name: String, dataDir: String, config: DataSource.DataSourceConfig,
        topicsWhitelist: Set[String], dataTypesWhitelist: Set[String]) =>
      val finalTopics = config.topics.filterKeys(key =>
        topicsWhitelist.isEmpty || topicsWhitelist.contains(key))
      val finalDataTypes = config.data_types.filterKeys(key =>
        dataTypesWhitelist.isEmpty || dataTypesWhitelist.contains(key))
      Class.forName(config.`class`).newInstance.asInstanceOf[DataSource]
        .ingest(dataDir, finalTopics, finalDataTypes)
  }
}
