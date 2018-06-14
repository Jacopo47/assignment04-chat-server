import controller.Dispatcher
import io.vertx.scala.core.Vertx
import io.vertx.scala.core.file.OpenOptions



object Main extends App {
  Vertx.vertx().deployVerticle(new Dispatcher())
}
