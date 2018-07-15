package model

import io.vertx.core.http.HttpMethod
import io.vertx.lang.scala.json.{Json, JsonObject}
import io.vertx.scala.ext.web.{Router, RoutingContext}
import redis.RedisClient


/**
  * Interfaccio per la gestione di una richiesta proveniente dal server di Vertx.
  */
trait Request {
  def router: Router

  def url: String

  def method: HttpMethod

  def handle: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit

  def handler(): Unit = {
    router.route(method, url).produces("application/json").handler(routingContext => {
      val res = ConsumeBeforeRes(routingContext)
      val data = Json.emptyObj()
      res.setData(data)
      handle(routingContext, data, res)
    })
  }
}


/**
  * Tipo di richiesta GET
  * @param router
  *        Oggetto che si occupa del routing
  * @param url
  *        URL da gestire
  * @param handle
  *        Funzione che si occupa di gestire la richiesta e di produrre una risposta
  */
case class GET(override val router: Router,
               override val url: String,
               override val handle: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit) extends Request {
  override val method = HttpMethod.GET

  handler()
}

/**
  * Tipo di richiesta POST
  * @param router
  *        Oggetto che si occupa del routing
  * @param url
  *        URL da gestire
  * @param handle
  *        Funzione che si occupa di gestire la richiesta e di produrre una risposta
  */
case class POST(override val router: Router,
                override val url: String,
                override val handle: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit) extends Request {
  override val method = HttpMethod.POST

  handler()
}

/**
  * Produttore / Consumatore per attendere il completamento delle future e delle interazioni con il database.
  * Una volta consumati tutti i "token" produce una risposta.
  * @param routingContext
  *         Oggetto con i riferimenti alla richiesta.
  */
case class ConsumeBeforeRes(routingContext: RoutingContext) {
  private var counter: Int = 0
  private var limit = 1
  private var data: JsonObject = _
  private var redisClient: RedisClient = _
  private var onCloseOperation: RedisClient => Unit = _

  def consume(): Unit = {
    counter += 1
    if (counter == limit) {
      responseJson()
      if (onCloseOperation != null) onCloseOperation(redisClient)
      counter = 0
      limit = 1
      data.clear()
    }
  }

  def initialize(limit: Int, redisClient: RedisClient = null, onClose: RedisClient => Unit = null): Unit = {
    setLimit(limit)
    setRedisClient(redisClient)
    setOnClose(onClose)
  }

  def initialize(limit: Int, onClose: RedisClient => Unit): Unit = {
    setLimit(limit)
    setOnClose(onClose)
  }

  def addProducer(qta: Int = 1): Unit = this.limit += qta

  def setLimit(limit: Int): Unit = this.limit = limit

  def setData(data: JsonObject): Unit = this.data = data

  def setRedisClient(redisClient: RedisClient): Unit = this.redisClient = redisClient

  def setOnClose(onClose: RedisClient => Unit): Unit = this.onCloseOperation = onClose

  private def responseJson(): Unit = {
    routingContext.response()
      .setChunked(true)
      .putHeader("Content-Type", "application/json")
      .write(data.encode())
      .end()
  }
}


