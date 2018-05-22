package data

import akka.actor.{Actor, ActorLogging}
import core.DataSource

class IngestService extends Actor with ActorLogging {
  override def receive: Receive = {
    case (name: String, dataDir: String, config: DataSource.DataSourceConfig) =>
      config.topics.foreach({ case (topicName, _) =>
        config.data_types.foreach({ case (typeName, _) =>
          log.info("Ingesting {}/{}/{}", name, topicName, typeName)
        })
      })
      Class.forName(config.`class`).newInstance.asInstanceOf[DataSource]
        .ingest(dataDir, config.topics, config.data_types)
  }
}
