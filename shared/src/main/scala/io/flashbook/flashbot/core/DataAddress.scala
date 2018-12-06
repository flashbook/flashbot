package io.flashbook.flashbot.core

import io.flashbook.flashbot.core.Slice.SliceId

final case class DataAddress(host: Option[String],
                             path: DataPath) {
  def withHost(host: String): DataAddress = copy(host = Some(host))
  def withHost(hostOpt: Option[String]): DataAddress = copy(host = hostOpt)

  def withTopic(topic: String) = copy(path = path.copy(topic = topic))
  def withSource(src: String) = copy(path = path.copy(source = src))
  def withType(ty: String) = copy(path = path.copy(dataType = ty))
}

object DataAddress {
  def wildcard = DataAddress(None, DataPath.wildcard)
}

