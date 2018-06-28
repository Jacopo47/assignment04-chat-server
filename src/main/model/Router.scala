package model

import io.vertx.core.http.HttpMethod
import io.vertx.lang.scala.json.JsonObject
import io.vertx.scala.ext.web.{Router, RoutingContext}
import redis.RedisClient


trait Request {
  def router: Router

  def url: String

  def method: HttpMethod

  def data: JsonObject

  def res: ConsumeBeforeRes

  def handle: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit

  def handler(): Unit = {
    res.setData(data)
    router.route(method, url).produces("application/json").handler(handle(_, data, res))
  }
}

case class GET(override val router: Router,
               override val url: String,
               override val handle: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit,
               override val data: JsonObject = new JsonObject(),
               override val res: ConsumeBeforeRes = ConsumeBeforeRes()) extends Request {
  override val method = HttpMethod.GET

  handler()
}

case class POST(override val router: Router,
               override val url: String,
               override val handle: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit,
               override val data: JsonObject = new JsonObject(),
               override val res: ConsumeBeforeRes = ConsumeBeforeRes()) extends Request {
  override val method = HttpMethod.POST

  handler()
}

case class ConsumeBeforeRes() {
  private var counter: Int = 0
  private var limit = 1
  private var routingContext: RoutingContext = _
  private var data: JsonObject = _
  private var redisClient: RedisClient = _
  private var onCloseOperation: RedisClient => Unit = _

  def consume(): Unit = {
    counter += 1
    if (counter == limit) {
      responseJson(routingContext, data)
       if (onCloseOperation != null) onCloseOperation(redisClient)
      counter = 0
      limit = 1
      data.clear()
    }
  }

  def initialize(routingContext: RoutingContext, limit: Int, redisClient: RedisClient = null, onClose: RedisClient => Unit = null): Unit = {
    setRoutingContext(routingContext)
    setLimit(limit)
    setRedisClient(redisClient)
    setOnClose(onClose)
  }

  def initialize(routingContext: RoutingContext, limit: Int, onClose: RedisClient => Unit): Unit = {
    setRoutingContext(routingContext)
    setLimit(limit)
    setOnClose(onClose)
  }
  def setRoutingContext(routingContext: RoutingContext): Unit = this.routingContext = routingContext

  def setLimit(limit: Int): Unit = this.limit = limit

  def setData(data: JsonObject): Unit = this.data = data

  def setRedisClient(redisClient: RedisClient): Unit = this.redisClient = redisClient

  def setOnClose(onClose: RedisClient => Unit): Unit = this.onCloseOperation = onClose

  private def responseJson(routingContext: RoutingContext, json: JsonObject): Unit = {
    routingContext.response()
      .setChunked(true)
      .putHeader("Content-Type", "application/json")
      .write(json.encode())
      .end()
  }
}


