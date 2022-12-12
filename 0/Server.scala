import java.net.InetSocketAddress

import akka.actor.{ Actor, ActorSystem, Props }
import akka.io.{ IO, Tcp }

import scala.io.StdIn

class SimplisticHandler extends Actor {
  import Tcp._
  def receive = {
    case Received(data) =>
      sender() ! Write(data)
    case PeerClosed     =>
      context.stop(self)
  }
}


class TcpManager extends Actor {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 9091))

  def receive = {
    case b @ Bound(_) =>
      println("Servering on port 9091")
      context.parent ! b

    case CommandFailed(_: Bind) => context.stop(self)

    case Connected(_, _) =>
      val handler = context.actorOf(Props[SimplisticHandler]())
      val connection = sender()
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
