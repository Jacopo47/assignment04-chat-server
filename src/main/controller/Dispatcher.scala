package controller

import io.vertx.core.http.HttpMethod
import io.vertx.lang.scala.ScalaVerticle
import io.vertx.lang.scala.json.{JsonArray, JsonObject}
import io.vertx.scala.ext.web.{Router, RoutingContext}
import redis.RedisClient

/*
  TODO
   Implementare risposte per:
    - POST user:* - name
    - GET user:* - hash con i dati dell'utente
    - GET user:*:chats - lista delle chat dell'utente
    - GET chat:* - lista con i messaggi della chat
*/

class responseIf(limit: Int)(res: => Unit) {
  private var counter: Int = 0

  def consume(): Unit = {
    counter += 1
    if (counter == limit) res
  }
}
class Dispatcher extends ScalaVerticle {
  implicit val akkaSystem = akka.actor.ActorSystem()
  val applicationJson: String = "application/json"
  val HOST: String = "localhost"
  val PORT: Int = 6379

  override def start(): Unit = {
    val router = Router.router(vertx)

    router.route(HttpMethod.GET, "/type/:id")
          .produces(applicationJson)
          .handler(routingGETRequest(_))

    router.route(HttpMethod.GET, "/user/:id")
        .produces(applicationJson)
        .handler(getUserData(_))

    vertx.createHttpServer()
      .requestHandler(router.accept _ ).listen(4700)

  }


  def getUserData(routingContext: RoutingContext): Unit = {
    val data = new JsonObject()
    val res = new responseIf(1) ({
      responseJson(routingContext, data)
    })

    val redis = RedisClient(HOST, PORT)
    var id = ""
    routingContext.request().getParam("id") match {
      case Some(value) => id = value
      case _ => id = "nil"
    }

    redis.hgetall(id).map(userData => {
      userData foreach {case (k,v) => data.put(k,v.utf8String)}
      res.consume()
    })
  }


  def routingGETRequest(routingContext: RoutingContext): Unit = {
    val data = new JsonObject()
    data.put("vals", new JsonArray())
    val res = new responseIf(3)({
      responseJson(routingContext, data)
    })

    val redis = RedisClient(HOST, PORT)


    val future = redis.get("foo")

    future.map(value => {
      println(value.get.utf8String)
      data.getJsonArray("vals").add(value.get.utf8String)
      res.consume()
    })


    val app = redis.get("foo1")

    app.map(value => {
      println(value.get.utf8String)
      data.getJsonArray("vals").add(value.get.utf8String)
      res.consume()
    })

    redis.hgetall("me").map(me => {
      val app = new JsonObject()
      me foreach {case (k, v) => app.put(k, v.utf8String)}
      data.put("me", app )
      res.consume()
    })

    routingContext.request().getParam("type") match {
      case Some(reqType) => data.put("type", reqType)
      case None => data.put("type", "none")
    }

    routingContext.request().getParam("id") match {
      case Some(id) => data.put("id", id)
      case None => data.put("id", "none")
    }




  }


  private def responseJson(routingContext: RoutingContext, json: JsonObject): Unit = {
    routingContext.response()
      .setChunked(true)
      .putHeader("Content-Type", applicationJson)
      .write(json.encode())
      .end()
  }

}
