package model

import akka.actor.{Actor, ActorSystem}
import controller.Utility.{CHATS, HOST, PASSWORD, PORT}
import model.Message.DeleteChatMsg
import redis.RedisClient

import scala.concurrent.ExecutionContext.Implicits.global

class BackgroundWorker extends Actor {
  implicit val akkaSystem: ActorSystem = akka.actor.ActorSystem()

  /**
    *
    * @return
    */
  override def receive: Receive = {
    case DeleteChatMsg(chatId) =>
      val redis = RedisClient(HOST, PORT, PASSWORD)

      redis.scard(CHATS + ":" + chatId + ":members").map(card => {
        if (card == 0) {
          val chatHead = CHATS + ":head:" + chatId
          val chatMembers = CHATS + ":" + chatId + ":members"
          val chat = CHATS + ":" + chatId
          redis.del(chat, chatHead, chatMembers).map(delKeys => {
            if (delKeys > 0) {
              redis.publish("chatDeleted." + chatId, chatId).map(_ => {
                redis.quit().map(_ => {
                  redis.stop()
                })
              })
            }
          })
        }
      })
  }
}
