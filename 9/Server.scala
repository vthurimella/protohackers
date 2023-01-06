import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.{IO, Tcp}

import java.net.InetSocketAddress
import scala.io.StdIn

class TcpManager(val jobManager: ActorRef) extends Actor {
  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 9091))

  def receive = {
    case b @ Bound(_) =>
      context.parent ! b

    case CommandFailed(_: Bind) =>
      context.stop(self)

    case Connected(_, _) =>
      val client = sender()
      val handler = context.actorOf(Props(new ClientHandler(client, jobManager)))
      client ! Register(handler)
  }
}

object Server extends App {
  implicit val system: ActorSystem = ActorSystem("Server")
  val jobManager = system.actorOf(Props[JobManager]())
  system.actorOf(Props(new TcpManager(jobManager)))
  println("Press enter to exit...")
  StdIn.readLine()
  system.terminate()
}
