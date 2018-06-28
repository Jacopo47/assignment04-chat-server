package controller

import java.net.InetSocketAddress

import akka.actor.{ActorSystem, Props}
import controller.Utility._
import io.vertx.lang.scala.ScalaVerticle
import io.vertx.lang.scala.json.{JsonArray, JsonObject}
import io.vertx.scala.ext.web.{Router, RoutingContext}
import model.{ConsumeBeforeRes, GET, POST}
import redis.RedisClient
import redis.actors.RedisSubscriberActor
import redis.api.pubsub.{Message, PMessage}

import scala.collection.mutable

object Utility {
  val applicationJson: String = "application/json"
  val USER = "user:"
  val CHATS = "chats"
  var HOST: String = "localhost"
  var PORT: Int = 6379
  var PASSWORD: Option[String] = Some("")
  val channels = Seq()
  val patterns = Seq("chat.*")
  val CHAT_ID = "chatId"
  val RESULT = "result"
}


class ConsumeThenRes(limit: Int)(res: => Unit) {
  private var counter: Int = 0

  def consume(): Unit = {
    counter += 1
    if (counter == limit) res
  }
}


class Dispatcher extends ScalaVerticle {
  implicit val akkaSystem: ActorSystem = akka.actor.ActorSystem()


  override def start(): Unit = {


    HOST = System.getenv("REDIS_HOST")
    PORT = System.getenv("REDIS_PORT").toInt
    PASSWORD = Some(System.getenv("REDIS_PW"))


    val router = Router.router(vertx)

    GET(router, "/", hello)

    //GET(router, "/type/:id", routingGETRequest)

    GET(router, "/user/:id", getUserData)

    POST(router, "/user/:id", setUserData)

    GET(router, "/user/:id/chats", getUserChats)

    POST(router, "/user/:id/chats", addChat)

    POST(router, "/user/:id/removeChats", removeChat)

    GET(router, "/chats/:id", getChat)

    GET(router, "/chats/:id/head", getChatData)

    GET(router, "/chats/new/", newChatID)

    POST(router, "/chats/:id/head", setChat)

    GET(router, "/user/:id/exist", existUser)


    vertx.createHttpServer()
      .requestHandler(router.accept _).listen(System.getenv("PORT").toInt)

    akkaSystem.actorOf(Props(classOf[SubscribeActor], channels, patterns))


  }

  /**
    * Restituisce i dati dell'utente, risponde a GET /user/:id
    */
  private val hello: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    res.initialize(routingContext, 1)

    data.put(RESULT, "Hello to everyone")
    res.consume()

  }

  private def closeRedisClient(client: RedisClient): Unit = {
    client.quit().map(fut => {
      if (!fut) {
        println("ERROR / Impossibile chiudere il client di redis")
      }
      client.stop()
    })
  }
  /**
    * Restituisce i dati dell'utente, risponde a GET /user/:id
    */
  private val getUserData: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)

    val id = USER + routingContext.request().getParam("id").getOrElse("").trim

    redis.exists(id).map(result => {
      if (result) {
        redis.hgetall(id).map(userData => {
          data.put(RESULT, true)
          data.put("user", new JsonObject())

          userData foreach { case (k, v) => data.getJsonObject("user").put(k, v.utf8String) }
          res.consume()
        })
      } else {
        data.put(RESULT, false)
        data.put("details", "L'utente non esiste")
        res.consume()
      }
    })

  }

  /**
    * Imposta i dati dell'utente, prende tutti i paramatri passati all'url: POST /user/:id?
    * e li associa alla chiave user:id
    *
    * Restituisce la chiave result che può essere TRUE o FALSE
    *
    */
  private val setUserData: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)

    val params = new mutable.HashMap[String, String]
    routingContext.queryParams().names().foreach(e => {
      val value: String = routingContext.request().getParam(e).getOrElse("")

      if (!value.isEmpty) params.put(e.trim, value.trim)
      /*routingContext.request().getParam(e) match {
        case Some(value) => if (!value.isEmpty) params.put(e.trim, value.trim)
      }*/
    })

    val id: String = USER + routingContext.request().getParam("id").getOrElse("").trim

    redis.hmset(id, params.toMap).map(result => {
      data.put(RESULT, result)
      res.consume()
    })

  }


  /**
    * Risponde all'url GET /user/:id/chats
    *
    * Restituisce result:
    * TRUE
    * con la lista delle chat in cui è registrato l'utente
    * FALSE se
    *     - l'utente non esiste
    *     - l'utente non possiede chat
    *
    */
  private val getUserChats: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)


    val id: String = USER + routingContext.request().getParam("id").getOrElse("")
    redis.exists(id).map(result => {
      if (result) {
        redis.smembers(id + ":" + CHATS).map(result => {
          data.put(RESULT, true)

          if (result.isEmpty) {
            data.put(RESULT, false)
            data.put("details", "Nessuna chat per l'utente")
          } else {
            data.put("chats", new JsonArray())
            result.foreach(e => data.getJsonArray("chats").add(e.utf8String))
          }

          res.consume()
        })
      } else {
        data.put(RESULT, false)
        data.put("details", "L'utente non esiste")
        res.consume()
      }
    })

  }


  /**
    * Risponde a GET /chats/:id
    *
    * Result:
    * TRUE se:
    *     - esiste la chat e ne restituisce gli elementi in un JsonArray (chat) con elementi: timestamp (Long) / msg (String) / sender (String)
    * FALSE se
    *     - non esiste la chat
    */
  private val getChat: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)

    val id = CHATS + ":" + routingContext.request().getParam("id").getOrElse("").trim
    val headId = CHATS + ":head:" + routingContext.request().getParam("id").getOrElse("").trim

    redis.exists(id).map(result => {
      if (result) {
        redis.hget(headId, "title").map(title => {
          data.put(RESULT, true)
          data.put("title", title.get.utf8String)
          data.put("chat", new JsonArray())
          redis.lrange(id, 0, -1).map(msgList => {
            msgList.foreach(e => {
              val msg = new JsonObject(e.utf8String)
              data.getJsonArray("chat")
                .add(new JsonObject()
                  .put("timestamp", msg.getLong("timestamp"))
                  .put("msg", msg.getString("msg"))
                  .put("sender", msg.getString("sender")))
            })
            res.consume()
          })
        })
      } else {
        data.put(RESULT, false)
        data.put("details", "La chat indicata non esiste")
        res.consume()
      }
    })

  }


  /**
    * Risponde all'url /user/:id/chats?chat=idChat
    *
    * È importante fornire il paramentro chat altimenti risponde con errore
    *
    * Ritorna:
    * TRUE se l'inserimento ha avuto successo
    * FALSE se:
    *     - la chat non è stata fornita
    *     - l'utente non esiste
    *     - la chat è già presente tra quelle dell'utente
    *
    */
  private val addChat: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)


    val id: String = USER + routingContext.request().getParam("id").get.trim
    val chat: String = routingContext.request.getParam("chat").getOrElse("").trim

    if (chat.trim.isEmpty) {
      data.put(RESULT, false)
      data.put("details", "Non è stata indicata alcuna chat come parametro")
      res.consume()
    }

    redis.exists(id).map(result => {
      if (result) {
        redis.sadd(id + ":" + CHATS, chat).map(result => {
          if (result > 0) {
            //Inserimento riuscito
            data.put(RESULT, true)
          } else {
            //Inserimento fallito, chat già contenuta nel set
            data.put(RESULT, false)
            data.put("details", "Chat già presente per l'utente")
          }
          res.consume()
        })
      } else {
        data.put(RESULT, false)
        data.put("details", "L'utente non esiste")
        res.consume()
      }
    })

  }


  /**
    * Risponde all'url /user/:id/removeChats?chat=idChat
    *
    * È importante fornire il paramentro chat altimenti risponde con errore
    *
    * Ritorna:
    * TRUE se la rimozione ha avuto successo
    * FALSE se:
    *     - la chat non è stata fornita
    *     - l'utente non esiste
    *
    */
  private val removeChat: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)


    val id: String = USER + routingContext.request().getParam("id").get.trim
    val chat: String = routingContext.request.getParam("chat").getOrElse("").trim

    if (chat.trim.isEmpty) {
      data.put(RESULT, false)
      data.put("details", "Non è stata indicata alcuna chat come parametro")
      res.consume()
    }

    redis.exists(id).map(result => {
      if (result) {
        redis.srem(id + ":" + CHATS, chat).map(result => {
          if (result > 0) {
            //Rimozione riuscita
            data.put(RESULT, true)
          } else {
            //Rimozione fallita chat non presente
            data.put(RESULT, false)
            data.put("details", "Chat non presente per l'utente")
          }
          res.consume()
        })
      } else {
        data.put(RESULT, false)
        data.put("details", "L'utente non esiste")
        res.consume()
      }
    })

  }

  /**
    * Risponde a GET /chats/new/
    *
    * Resistisce alla chiave 'id' un valore univoco da associare alla chat.
    *
    */
  private val newChatID: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)

    redis.incr(CHAT_ID).map(newChatID => {
      data.put("id", newChatID.toString)
      res.consume()
    })
  }


  /**
    * Imposta i dati dell'utente, prende tutti i paramatri passati all'url: POST /chats/:id/?
    * e li associa alla chiave user:id
    *
    * Restituisce la chiave result che può essere TRUE o FALSE
    *
    */
  private val setChat: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)

    val params = new mutable.HashMap[String, String]
    routingContext.queryParams().names().foreach(e => {
      val value: String = routingContext.request().getParam(e).getOrElse("")

      if (!value.isEmpty) params.put(e.trim, value.trim)
      /*routingContext.request().getParam(e) match {
        case Some(value) => if (!value.isEmpty) params.put(e.trim, value.trim)
      }*/
    })

    val id: String = CHATS + ":head:" + routingContext.request().getParam("id").getOrElse("").trim

    redis.hmset(id, params.toMap).map(result => {
      data.put(RESULT, result)
      res.consume()
    })

  }

  /**
    * Restituisce i dati della chat, risponde a GET /chats/:id/head
    */
  private val getChatData: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)

    val id = CHATS + ":head:" + routingContext.request().getParam("id").getOrElse("").trim

    redis.exists(id).map(result => {
      if (result) {
        redis.hgetall(id).map(chatData => {
          data.put(RESULT, true)
          data.put("chat", new JsonObject())

          chatData foreach { case (k, v) => data.getJsonObject("chat").put(k, v.utf8String) }
          res.consume()
        })
      } else {
        data.put(RESULT, false)
        data.put("details", "La chat non esiste")
        res.consume()
      }
    })

  }

  /**
    * Risponde a GET /user/:id/exists
    *
    * Alla chiave result associa true se l'utente esiste, false altrimenti
    *
    */
  private val existUser: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(routingContext, 1, redis, closeRedisClient)

    val id: String = USER + routingContext.request().getParam("id").getOrElse("").trim
    redis.exists(id).map(exists => {
      if (exists) {
        data.put(RESULT, true)
      } else {
        data.put(RESULT, false)
      }
      res.consume()
    })
  }

  /*
  val routingGETRequest: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    res.initialize(routingContext, 3)

    try {

      data.put("vals", new JsonArray())


      val redis = RedisClient(HOST, PORT, PASSWORD)


      val future = redis.get("foo")

      future.map(value => {
        println("foo " concat value.get.utf8String)
        data.getJsonArray("vals").add(value.get.utf8String)
        res.consume()
      })


      val app = redis.get("foo1")

      app.map(value => {

        println("in get foo1" + value.get.utf8String)
        data.getJsonArray("vals").add(value.get.utf8String)
        res.consume()
      })


      redis.exists("me") map (exist => {
        if (exist) {
          redis.hgetall("me").map(me => {
            println("in hgetall")
            val app = new JsonObject()
            if (me.nonEmpty) {
              me foreach { case (k, v) =>
                println(k + " / " + v.utf8String)
                app.put(k, v.utf8String)
              }
            }
            data.put("me", app)
            res.consume()
          })
        } else {
          data.put("me", "chiave inesistente")
          res.consume()
        }
      })


      routingContext.request().getParam("type") match {
        case Some(reqType) => data.put("type", reqType)
        case None => data.put("type", "none")
      }

      routingContext.request().getParam("id") match {
        case Some(id) => data.put("id", id)
        case None => data.put("id", "none")
      }
    } catch {
      case e: Exception => println(e.printStackTrace())
    }


  }*/




}


class SubscribeActor(channels: Seq[String] = Nil, patterns: Seq[String] = Nil)
  extends RedisSubscriberActor(
    new InetSocketAddress(HOST, PORT),
    channels,
    patterns,
    PASSWORD,
    onConnectStatus = connected => {
      println(s"connected: $connected")
    }) {

  implicit val akkaSystem: ActorSystem = akka.actor.ActorSystem()

  val redis = RedisClient(HOST, PORT, PASSWORD)

  def onMessage(message: Message) {
    println(s"message received: $message")
  }

  def onPMessage(pmessage: PMessage) {
    val chat = pmessage.channel.replaceFirst("chat.", CHATS + ":")
    val msg = pmessage.data.utf8String

    val timestamp = System.currentTimeMillis()

    val element = new JsonObject().put("timestamp", timestamp)
      .put("msg", msg).put("sender", "unknown")

    redis.rpush(chat, element.encode())
  }
}


