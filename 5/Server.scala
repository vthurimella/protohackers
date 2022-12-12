import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Tcp.{PeerClosed, Received, Write}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import java.net.InetSocketAddress
import scala.io.StdIn


// From client to backend
case class ForwardToBackend(msg: String)
case class CloseConnect()

// From backend to client
case class ForwardToUser(msg: ByteString)

object Client {
  val BackendHostname = "chat.protohackers.com"
  val BackendPort = 16963
}

class Client(proxy: ActorRef) extends Actor {
  import Client._
  import Tcp._
  import context.system

  IO(Tcp) ! Connect(new InetSocketAddress(BackendHostname, BackendPort))

  def receive = {
    case CommandFailed(_: Connect) =>
      println("connect failed")
      context.stop(self)

    case c @ Connected(_, _) =>
      val connection = sender()
      connection ! Register(self)
      context.become {
        case CloseConnect() =>
          println("connection closed")
          context.stop(self)
        case ForwardToBackend(msg) =>
          connection ! Write(ByteString(msg))

        case Received(data) =>
          proxy ! ForwardToUser(data)

        // Failure cases
        case CommandFailed(w: Write) =>
          // O/S buffer was full
          println("write failed")
        case "close" =>
          connection ! Close
        case _: ConnectionClosed =>
          println("connection closed")
          context.stop(self)
      }
  }
}

object Proxy {
  val TonysBoguscoinAddres = "7YWHMfk9JZe0LM0g1ZauHuiSxhI"

  def isBoguscoinAddress(token: String) =
    token.startsWith("7") &&
      26 <= token.length &&
      token.length <= 35 &&
      token.forall(Character.isLetterOrDigit)

  def replaceAddress(msg: String) =
    msg.split(" ", -1).map {
      case token if isBoguscoinAddress(token) => TonysBoguscoinAddres
      case token => token
    }.mkString(" ")
}


class Proxy(val user: ActorRef) extends Actor {
  import Proxy._
  val backend = context.actorOf(Props(new Client(self)))
  var buffer = ""

  def receive = {
    case ForwardToUser(msg) =>
      user ! Write(ByteString(replaceAddress(msg.utf8String)))
    case PeerClosed =>
      user ! CloseConnect()
      context.stop(self)
    case Received(data) =>
      val msg = data.utf8String
      msg.contains('\n') match {
        case true =>
          val lInd = msg.lastIndexOf('\n')
          (buffer + msg.substring(0, lInd + 1)).split("\n").foreach { case line =>
            backend ! ForwardToBackend(replaceAddress(line) + "\n")
          }
          buffer = msg.substring(lInd + 1)
        case false =>
          buffer += msg
      }
  }
}

class TcpManager extends Actor {
  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 9091))

  def receive = {
    case b @ Bound(_) =>
      context.parent ! b

    case CommandFailed(_: Bind) =>
      context.stop(self)

    case Connected(_, _) =>
      val connection = sender
      val handler = context.actorOf(Props(new Proxy(connection)))
      connection ! Register(handler)
  }
}

object Server extends App {
  implicit val system: ActorSystem = ActorSystem("Server")
  system.actorOf(Props(classOf[TcpManager]))
  println("Press enter to exit...")
  StdIn.readLine()
  system.terminate()
}
