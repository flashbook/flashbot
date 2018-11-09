import com.google.inject.AbstractModule
import io.flashbook.flashbot.service.Control

class Module extends AbstractModule {
  override def configure() = {
    bind(classOf[Control]).asEagerSingleton()
  }
}
