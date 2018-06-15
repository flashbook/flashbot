package data

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import core.{DataSource, Utils}

class IngestService extends Actor with ActorLogging {
  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = Utils.buildMaterializer

  override def receive: Receive = {
    case (name: String, dataDir: String, config: DataSource.DataSourceConfig,
        topicsWhitelist: Seq[String]) =>
      val finalTopics = config.topics .filterKeys(key =>
        topicsWhitelist.isEmpty || topicsWhitelist.contains(key))
      Class.forName(config.`class`).newInstance.asInstanceOf[DataSource]
        .ingest(dataDir, finalTopics, config.data_types)
  }
}
