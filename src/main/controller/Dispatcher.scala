package controller

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props}
import controller.Utility._
import io.vertx.lang.scala.ScalaVerticle
import io.vertx.lang.scala.json.{Json, JsonArray, JsonObject}
import io.vertx.scala.ext.web.{Router, RoutingContext}
import model.message.DeleteChatMsg
import model.{BackgroundWorker, ConsumeBeforeRes, GET, POST}
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
  val DETAILS = "details"
  val MEMBERS = "members"
  val MSG = "msg"
  val SENDER = "sender"
}


class Dispatcher extends ScalaVerticle {
  implicit val akkaSystem: ActorSystem = akka.actor.ActorSystem()

  val backgroundWorker: ActorRef = akkaSystem.actorOf(Props(classOf[BackgroundWorker]))
  override def start(): Unit = {


    /*
      Si prelevano le variabili di ambiente.
    */
    HOST = System.getenv("REDIS_HOST")
    PORT = System.getenv("REDIS_PORT").toInt
    PASSWORD = Some(System.getenv("REDIS_PW"))


    val router = Router.router(vertx)

    GET(router, "/", hello)

    GET(router, "/user/:id", getUserData)

    POST(router, "/user/:id", setUserData)

    GET(router, "/user/:id/chats", getUserChats)

    POST(router, "/user/:id/chats", addChat)

    POST(router, "/user/:id/removeChats", removeChat)

    GET(router, "/chats/:id", getChat)

    GET(router, "/allChats", getAllChats)

    GET(router, "/chats/:id/head", getChatData)

    GET(router, "/chats/new/", newChatID)

    POST(router, "/chats/:id/head", setChat)

    GET(router, "/user/:id/exist", existUser)


    vertx.createHttpServer()
      .requestHandler(router.accept _).listen(System.getenv("PORT").toInt)

    akkaSystem.actorOf(Props(classOf[SubscribeActor], channels, patterns))


  }

  /**
    * Schermata di "Welcome"
    */
  private val hello: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    res.initialize(1)

    data.put(RESULT, "Hello to everyone")
    res.consume()

  }



  /**
    * Restituisce i dati dell'utente, risponde a GET /user/:id
    */
  private def getUserData: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(1, redis, closeRedisClient)

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
    res.initialize(1, redis, closeRedisClient)

    if (routingContext.queryParams().isEmpty()) {
      data.put(RESULT, false)
      data.put(DETAILS, "Nessun parametro fornito in input")
      res.consume()
    }

    val params = new mutable.HashMap[String, String]
    routingContext.queryParams().names().foreach(e => {
      val value: String = routingContext.request().getParam(e).getOrElse("")

      if (!value.isEmpty) params.put(e.trim, value.trim)
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
    res.initialize(1, redis, closeRedisClient)


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
  private def getChat: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(2, redis, closeRedisClient)

    val id = routingContext.request().getParam("id").getOrElse("").trim
    val chatId = CHATS + ":" + id
    val headId = CHATS + ":head:" + id
    val keyChatMembers = CHATS + ":" + id + ":members"

    redis.exists(headId).map(result => {
      if (result) {
        redis.hget(headId, "title").map(title => {
          data.put(RESULT, true)
          data.put("title", title.get.utf8String)
          data.put("chat", new JsonArray())
          redis.lrange(chatId, 0, -1).map(msgList => {
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

    redis.smembers(keyChatMembers).map(members => {
      res.addProducer(members.length)
      data.put(MEMBERS, new JsonArray())
      members foreach (m => {
        redis.hget(USER + m.utf8String, "name").map(name => {
          val user: JsonObject = new JsonObject()
          user.put("id", m.utf8String)
          user.put("name", name.get.utf8String)
          data.getJsonArray(MEMBERS).add(user)
          res.consume()
        })
      })
      res.consume()
    })
  }


  /**
    * Risponde a GET /chats/all
    *
    * Una lista gli id delle chat presenti nel sistema
    */
  private def getAllChats: (RoutingContext, JsonObject, ConsumeBeforeRes) => Unit = (routingContext, data, res) => {
    val redis = RedisClient(HOST, PORT, PASSWORD)
    res.initialize(1, redis, closeRedisClient)

    data.put(CHATS, Json.emptyArr())

    val chats = data.getJsonArray(CHATS)

    val searchPattern = "chats:head:*"
    redis.keys(searchPattern).map(keys => {
      if (keys.isEmpty) {
        data.put(RESULT, false)
        data.put(DETAILS, "Nessuna chat registrata")
      } else {
        data.put(RESULT, true)
        keys foreach (k => chats.add(k.replace("chats:head:", "")))
      }
      res.consume()
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
    res.initialize(1, redis, closeRedisClient)

    val user: String = routingContext.request().getParam("id").get.trim
    val id: String = USER + user
    val chat: String = routingContext.request.getParam("chat").getOrElse("").trim

    if (chat.trim.isEmpty) {
      data.put(RESULT, false)
      data.put("details", "Non è stata indicata alcuna chat come parametro")
      res.consume()
    }

    redis.exists(id).map(result => {
      if (result) {
        //Era impostato un solo produttore nel caso in cui la chat non fosse indicata
        //Arrivato a questo punto ne va aggiunto uno ulteriore visto le due chiamate da eseguire
        res.addProducer()

        val keyChatMembers = CHATS + ":" + chat + ":members"

        redis.sadd(keyChatMembers, user).map(result => {
          if (result > 0) {
            data.put(RESULT + "_" + CHATS, true)
          } else {
            data.put(RESULT + "_" + CHATS, false)
            data.put(DETAILS + "_" + CHATS, "Utente già presente tra i membri della chat")
          }

          res.consume()
        })
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
    res.initialize(1, redis, closeRedisClient)


    val user: String = routingContext.request().getParam("id").get.trim
    val id: String = USER + user
    val chat: String = routingContext.request.getParam("chat").getOrElse("").trim

    if (chat.trim.isEmpty) {
      data.put(RESULT, false)
      data.put("details", "Non è stata indicata alcuna chat come parametro")
      res.consume()
    }

    redis.exists(id).map(result => {
      if (result) {
        //Era impostato un solo produttore nel caso in cui la chat non fosse indicata o l'utente non fosse presente
        //Arrivato a questo punto ne va aggiunto uno ulteriore visto le due chiamate da eseguire
        res.addProducer()

        val keyChatMembers = CHATS + ":" + chat + ":members"

        redis.srem(keyChatMembers, user).map(result => {
          if (result > 0) {
            backgroundWorker ! DeleteChatMsg(chat)
            data.put(RESULT + "_" + CHATS, true)
          } else {
            data.put(RESULT + "_" + CHATS, false)
            data.put(DETAILS + "_" + CHATS, "Utente non presente tra i membri della chat")
          }

          res.consume()
        })
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
    res.initialize(1, redis, closeRedisClient)

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
    res.initialize(1, redis, closeRedisClient)

    val params = new mutable.HashMap[String, String]
    routingContext.queryParams().names().foreach(e => {
      val value: String = routingContext.request().getParam(e).getOrElse("")

      if (!value.isEmpty) params.put(e.trim, value.trim)
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
    res.initialize(1, redis, closeRedisClient)

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
    res.initialize(1, redis, closeRedisClient)

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


  /**
    * Metodo che si occupa di chiudere il client di redis fornito come parametro e di stopparne l'attore
    * @param client
    * Redis client
    */
  private def closeRedisClient(client: RedisClient): Unit = {
    client.quit().map(fut => {
      if (!fut) {
        println("ERROR / Impossibile chiudere il client di redis")
      }
      client.stop()
    })
  }
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
    try {
      val chat = pmessage.channel.replaceFirst("chat.", CHATS + ":")
      val complexMsg = Json.fromObjectString(pmessage.data.utf8String)

      var sender = complexMsg.getString(SENDER)
      if (sender == null) sender = "unknown"
      val msg = complexMsg.getString(MSG)
      val timestamp = System.currentTimeMillis()

      val element = new JsonObject().put("timestamp", timestamp)
        .put("msg", msg).put("sender", sender)

      redis.rpush(chat, element.encode())
    } catch {
      case ex: Exception => println("Error on elaborate msg: " + pmessage.data.utf8String + "\nDetails: " + ex.getMessage)
    }

  }
}


