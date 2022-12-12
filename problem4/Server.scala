import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Tcp.{PeerClosed, Received, Write}
import akka.event.Logging
import akka.io.{IO, Udp}
import akka.util.ByteString

import java.net.InetSocketAddress
import scala.io.StdIn

case class Insert(key: String, value: String)
case class Retrieve(key: String, user: ActorRef, remote: InetSocketAddress)

object Store {
  val VersionKey = "version"
  val VersionValue = "Best UDP server 1.0"
}

class Store extends Actor {
  import Store._
  var db: Map[String, String] = Map()

  def receive = {
    case Insert(k, v) =>
      db += (k -> v)
    case Retrieve(k, user, remote) =>
      k == VersionKey match {
        case true =>
          user ! Udp.Send(ByteString(s"${VersionKey}=${VersionValue}"), remote)
        case false =>
          user ! Udp.Send(ByteString(s"${k}=${db.getOrElse(k, "")}"), remote)
      }

  }
}

class UdpServer(host: String = "localhost", port: Int = 0, store: ActorRef) extends Actor {
  import context.system
  val log = Logging(context.system, this.getClass)

  override def preStart(): Unit = {
    log.info(s"Starting UDP Server on $host:$port")
    IO(Udp) ! Udp.Bind(self, new InetSocketAddress(host, port))
  }

  def receive: Receive = {
    case Udp.Bound(local) =>
      log.info(s"UDP Server is listening to ${local.getHostString}:${local.getPort}")

    case Udp.Received(data, remote) =>
      val msg = data.utf8String
      msg.contains("=") match {
        case true =>
          val ind = msg.indexOf("=")
          val key = msg.substring(0, ind)
          val value = msg.substring(ind+1)
          store ! Insert(key, value)
        case false =>
          store ! Retrieve(msg, sender, remote)
      }

    case Udp.Unbind =>
      sender ! Udp.Unbind

    case Udp.Unbound =>
      context.stop(self)
  }

  override def postStop(): Unit = {
    log.info(s"Stopping UDP Server.")
  }
}


object Server extends App {
  implicit val system: ActorSystem = ActorSystem("Server")
  val storeRef = system.actorOf(Props[Store])
  system.actorOf(Props(classOf[UdpServer], "0.0.0.0", 9091, storeRef))
  println("Press enter to exit...")
  StdIn.readLine()
  system.terminate()
}
