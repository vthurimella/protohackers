import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Tcp.{Closed, PeerClosed, Received, Write}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import scala.io.StdIn

case class Request(
  `type`: Byte,
  one: Int,
  two: Int,
)

object SimplisticHandler {
  val PacketSize = 9
}

class SimplisticHandler extends Actor {
  import SimplisticHandler._
  var table: Map[Int, Int] = Map.empty[Int, Int]
  var buffer: ByteString = ByteString()


  def respondAndClose(senderRef: ActorRef) = {
    senderRef ! Closed
    context.stop(self)
  }

  def receive = {
    case Received(data) =>
      buffer ++= data
      if(buffer.length >= PacketSize) {
        val grouped = buffer.grouped(PacketSize).toList
        val groups = buffer.length / 9
        if (buffer.length % PacketSize != 0) buffer = grouped(groups)
        else buffer = ByteString()
        grouped.take(groups).foreach { packet =>
          println(packet)
          val bb = packet.asByteBuffer
          val req = Request(bb.get, bb.getInt, bb.getInt)
          println(req)
          req.`type` match {
            case 'I' if !table.contains(req.one) =>
              table += (req.one -> req.two)
            case 'Q' =>
              val prices = table.filter { case (k, _) => req.one <= k && k <= req.two }.map(_._2.toLong)
              val res = prices.size == 0 || req.one > req.two match {
                case true => 0
                case false => (prices.sum / prices.size).toInt
              }
              val resByteStr = ByteString(ByteBuffer.allocate(4).putInt(res).array())
              sender() ! Write(resByteStr)
            case _ =>
              respondAndClose(sender())
          }
        }
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
