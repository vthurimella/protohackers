import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}
import akka.io.Tcp.{PeerClosed, Received, Write}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import java.net.InetSocketAddress
import java.util.Date
import scala.concurrent.duration.Duration
import scala.io.StdIn
import scala.concurrent.duration._
import java.time.LocalDate
import java.time.ZoneId

import akka.io.Tcp


case class Client(client: ActorRef) extends Actor {
  import Network._
  import Tcp._
  private var buffer: Array[Byte] = Array.empty[Byte]

  def receive = {
    case Received(data) =>
      val (rest, msgs, status) = Network.deserialize(buffer, data.toArray)
      buffer = rest
      status match {
        case Network.Success =>
          msgs.foreach { msg =>
            println(msg)
            msg match {
              case hello: Hello =>
                client ! Write(ByteString(Network.serialize(Network.Hello())))
            }
          }
        case e =>
          client ! Write(ByteString(Network.serialize(Network.Error(message = "Received invalid message"))))
      }

    case PeerClosed =>
      context.stop(self)
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
      val handler = context.actorOf(Props(Client(connection)))
      connection ! Register(handler)
  }
}

object Server extends App {
  implicit val system: ActorSystem = ActorSystem("Server")
//  val disRef = system.actorOf(Props(new RoadToDispatcher))
  system.actorOf(Props(new TcpManager()))
  println("Press enter to exit...")
  StdIn.readLine()
  system.terminate()
}
