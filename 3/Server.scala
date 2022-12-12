import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Tcp.{PeerClosed, Received, Write}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import java.net.InetSocketAddress
import scala.io.StdIn

trait UserState
case object UserConnected extends UserState
case object Joined extends UserState

// From User to RoomManager
case class JoinRoom(user: String, handler: ActorRef)
case class BroadcastMessage(from: String, msg: String)
case class LeaveRoom(user: String)

// From RoomManager to User
case class AddedToRoom(users: Seq[String])
case class UserExistsError(name: String)
case class ForwardToUser(msg: String)


object RoomManager {
  def enteredRoomMessage(name: String) = s"* $name has entered the room\n"
  def leaveRoomMessage(name: String) = s"* $name has left the room\n"
}

class RoomManager extends Actor {
  import RoomManager._
  var userMap: Map[String, ActorRef] = Map()

  def receive = {
    case LeaveRoom(user) =>
      userMap -= user
      userMap.foreach { case (_, ar) =>
        ar ! ForwardToUser(leaveRoomMessage(user))
      }
    case JoinRoom(user, handler) =>
      userMap.keySet.contains(user) match {
        case true =>
          handler ! UserExistsError(user)
        case false =>
          handler ! AddedToRoom(userMap.map(_._1).toSeq)
          userMap.foreach { case (_, ar) =>
            ar ! ForwardToUser(enteredRoomMessage(user))
          }
          userMap += user -> handler
      }
    case BroadcastMessage(from, msg) =>
      userMap.filterNot(_._1 == from).foreach { case (_, ar) =>
        ar ! ForwardToUser(s"[${from}] $msg")
      }
    case msg =>
      println(msg)
      println("Received unknown Message")
  }
}

object SimplisticHandler {
  def userExistsMessage(name: String) = s"$name is already in the room. Please use a different name.\n"
  def invalidName(name: String) = s"$name isn't valid. Please use name with only alphanumeric characters.\n"
  def roomNames(names: Seq[String]) = s"* The room contains: ${names.mkString(", ")}\n"
  def validName(name: String) = name.forall(ch => Character.isLetterOrDigit(ch))
}

class SimplisticHandler(val connection: ActorRef, val roomManagerRef: ActorRef) extends Actor {
  import SimplisticHandler._
  var state: UserState = UserConnected
  var nameOpt: Option[String] = None
  var buffer = ""

  def receive = {
    case ForwardToUser(msg) =>
      connection ! Write(ByteString(msg))
    case UserExistsError(u) =>
      // Respond with error message
      connection ! Write(ByteString(userExistsMessage(u)))
      context.stop(self)
    case AddedToRoom(userList) =>
      // Account presence to sender
      connection ! Write(ByteString(roomNames(userList)))
      println("Wrote entrance  message")
      state = Joined
    // Update state to Joined
    case Received(data) if state == UserConnected =>
      val name = data.utf8String.stripTrailing
      validName(name) match {
        case true =>
          // Register with room manager actor
          nameOpt = Some(name)
          roomManagerRef ! JoinRoom(name, self)
        case false =>
          connection ! Write(ByteString(invalidName(name)))
          context.stop(self)
      }
    case Received(data) if state == Joined =>
      // Broadcast to other users
      nameOpt match {
        case Some(name) =>
          val msg = data.utf8String
          msg.contains("\n") match {
            case true =>
              buffer += msg
              roomManagerRef ! BroadcastMessage(name, buffer)
              buffer = ""
            case false =>
              buffer += msg
          }
        case None =>
          println("Name not set for handler that is trying to broadcast message")
      }
    case PeerClosed =>
      nameOpt.filter(_ => state == Joined).foreach { name =>
        roomManagerRef ! LeaveRoom(name)
      }
      context.stop(self)
    case _ =>
      println("Received unknown message")
  }
}


object TcpManager {
  val WelcomeMessage = "Welcome to budgetchat! What shall I call you?\n"
}

class TcpManager extends Actor {
  import Tcp._
  import TcpManager._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 9091))

  val roomManager = context.actorOf(Props[RoomManager]())

  def receive = {
    case b @ Bound(_) =>
      context.parent ! b

    case CommandFailed(_: Bind) =>
      context.stop(self)

    case Connected(_, _) =>
      val connection = sender
      val handler = context.actorOf(Props(new SimplisticHandler(connection, roomManager)))
      connection ! Register(handler)
      connection ! Write(ByteString(WelcomeMessage))
  }
}


object Server extends App {
  implicit val system: ActorSystem = ActorSystem("Server")
  system.actorOf(Props(classOf[TcpManager]))
  println("Press enter to exit...")
  StdIn.readLine()
  system.terminate()
}
