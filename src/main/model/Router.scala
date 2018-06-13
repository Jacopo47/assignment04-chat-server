package model

import io.vertx.lang.scala.json.JsonObject
import io.vertx.scala.ext.web.RoutingContext


trait Request {
  def routingContext: RoutingContext

  def url: String

  def handler: (RoutingContext, ResponseIf) => Unit

  def data: JsonObject

  def res = new ResponseIf(1) {
    responseJson(routingContext, data)
  }

  private def responseJson(routingContext: RoutingContext, json: JsonObject): Unit = {
    routingContext.response()
      .setChunked(true)
      .putHeader("Content-Type", "application/json")
      .write(json.encode())
      .end()
  }
}

class ResponseIf(consumerLimit: Int)(res: => Unit) {
  private var counter: Int = 0
  private var limit = consumerLimit

  def consume(): Unit = {
    counter += 1
    if (counter == limit) res
  }

  def setLimit(limit: Int): Unit = this.limit = limit
}


private case class GET(routingContext: RoutingContext, url: String, method: RoutingContext => Unit) {

}
