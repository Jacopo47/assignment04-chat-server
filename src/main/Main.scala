import controller.Dispatcher
import io.vertx.scala.core.Vertx

object Main extends App {
  Vertx.vertx().deployVerticle(new Dispatcher())
}
