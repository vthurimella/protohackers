import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}
import akka.io.Tcp.{PeerClosed, Received, Write}
import akka.event.Logging
import akka.io.{IO, Udp}
import akka.util.ByteString

import java.util.regex.Pattern
import scala.concurrent.duration._
import java.net.InetSocketAddress
import scala.io.StdIn


case class AppData(sessionId: Int, data: String)
case class Send(sessionId: Int, data: String)

class Application(server: ActorRef) extends Actor {
  var prevMessages = Map.empty[Int, String]
  override def receive: Receive = {
    case AppData(sessionId, data) =>
      val prev = prevMessages.getOrElse(sessionId, "") + data
      prev.contains("\n") && prev.length > 1 match {
        case true =>
          val sp = prev.split("\n", -1)
          val (proc, rest) = (sp.take(sp.length - 1), sp.last)
          proc.foreach { token =>
            server ! Send(sessionId, s"${token.reverse}\n")
          }
          prevMessages += (sessionId -> rest)
        case false =>
          prevMessages += (sessionId -> prev)
      }
  }
}

sealed abstract class Status
case object Unknown extends Status
case object ConnectReceived extends Status
case object ConnectAcked extends Status

case class SentState(
  acked: Int,
  buffer: String,
)

case class RecvState(
  acked: Int,
  recv: Int,
  chunks: Map[Int, String]
)

case class SessionState(
  remote: InetSocketAddress,
  status: Status,
  sessionId: Int,
  sent: SentState,
  recv: RecvState,
  lastAckSent: Option[LRCP.Packet] = None,
  lastDataSent: Option[LRCP.Packet] = None,
  closeState: Cancellable,
)

class UdpServer(host: String = "localhost", port: Int = 0) extends Actor {
  import LRCP._
  import context.system
  var state: Map[Int, SessionState] = Map.empty[Int, SessionState]
  val log = Logging(context.system, this.getClass)
  val App = context.system.actorOf(Props(new Application(self)))
  implicit val ec = context.dispatcher
  var udpSender = self

  def combine(start: Int, chunks: Map[Int, String]): (String, Int, Map[Int, String]) = {
    var i = start
    var res = ""
    var inRes: Set[Int] = Set()
    chunks.keys.toSeq.sorted.foreach { case pos =>
      if (i >= pos) {
        val c = chunks(pos).substring(i - pos)
        res += c
        i += c.length
        inRes += pos
      }
    }
    (res, i, chunks.filterKeys(p => !inRes.contains(p)))
  }

  def closeSession(sessionId: Int): Unit = {
    state.get(sessionId).foreach { s =>
      udpSender ! Udp.Send(ByteString(Writer(Close(sessionId))), s.remote)
    }
    state -= sessionId
  }

  context.system.scheduler.scheduleAtFixedRate(RetransmissionTimeout, RetransmissionTimeout)(() => {
    state.foreach { case (sessionId, state) =>
      val defaultAck = Ack(sessionId, 0)
      (Seq(state.lastAckSent.getOrElse(defaultAck)) ++ state.lastDataSent.toSeq).foreach { p =>
        udpSender ! Udp.Send(ByteString(Writer(p)), state.remote)
      }
    }
  })

  override def preStart(): Unit = {
    log.info(s"Starting UDP Server on $host:$port")
    IO(Udp) ! Udp.Bind(self, new InetSocketAddress(host, port))
  }

  def receive: Receive = {
    case Udp.Bound(local) =>
      log.info(s"UDP Server is listening to ${local.getHostString}:${local.getPort}")
      val conn = sender
      udpSender = conn

    case Send(sessionId, data) =>
      state.get(sessionId) match {
        case Some(curr) =>
          var pOpt: Option[Packet] = None
          var sentBuffer = curr.sent.buffer
          data.grouped(MaxDataSizeBytes).zipWithIndex.foreach { case (subdata, ind) =>
            pOpt = Some(Data(sessionId, sentBuffer.length, subdata))
            pOpt.foreach(p => udpSender ! Udp.Send(ByteString(Writer(p)), curr.remote))
            sentBuffer += subdata
          }
          state += (sessionId -> curr.copy(sent = curr.sent.copy(buffer = sentBuffer), lastDataSent = pOpt))
        case None =>
      }

    case Udp.Received(data, remote) =>
      val parsed = Parser(data.utf8String)
      parsed match {
        case Some(Connect(sessionId)) =>
          state.get(sessionId) match {
            case Some(curr) =>
              curr.closeState.cancel
              val p = Ack(sessionId, curr.recv.recv)
              val cs = context.system.scheduler.scheduleOnce(SessionExpiry)(closeSession(sessionId))
              sender ! Udp.Send(ByteString(Writer(p)), remote)
              state += (sessionId -> curr.copy(lastAckSent = Some(p), closeState = cs))
            case None =>
              val p = Ack(sessionId, 0)
              val cs = context.system.scheduler.scheduleOnce(SessionExpiry)(closeSession(sessionId))
              sender ! Udp.Send(ByteString(Writer(p)), remote)
              val sent = SentState(acked = 0, buffer = "")
              val recv = RecvState(acked = 0, recv = 0, chunks = Map.empty[Int,  String])
              val ss = SessionState(
                remote = remote,
                sessionId = sessionId,
                status = ConnectAcked,
                sent = sent,
                recv = recv,
                lastAckSent = Some(p),
                closeState = cs
              )
              state += (sessionId -> ss)
          }

        case Some(Data(sessionId, position, data)) =>
          state.get(sessionId) match {
            case Some(curr) =>
              curr.closeState.cancel
              (position >= curr.recv.recv, position == curr.recv.recv) match {
                case (true, false) =>
                  // Resend ack
                  val p = Ack(sessionId = sessionId, length = curr.recv.recv)
                  val cs = context.system.scheduler.scheduleOnce(SessionExpiry)(closeSession(sessionId))
                  udpSender ! Udp.Send(ByteString(Writer(p)), curr.remote)
                  val updateSS = curr.copy(
                    lastAckSent = Some(p),
                    closeState = cs,
                    recv = curr.recv.copy(chunks = curr.recv.chunks + (position -> data))
                  )
                  state += (sessionId -> updateSS)
                case (true, true) =>
                  val (combinedData, received, otherChunks) = combine(curr.recv.recv, curr.recv.chunks + (position -> data))
                  val p = Ack(sessionId = sessionId, length = received)
                  val cs = context.system.scheduler.scheduleOnce(SessionExpiry)(closeSession(sessionId))
                  udpSender ! Udp.Send(ByteString(Writer(p)), curr.remote)
                  val updatedSS = curr.copy(
                    recv = curr.recv.copy(acked = received, recv = received, chunks = otherChunks),
                    lastAckSent = Some(p),
                    closeState = cs,
                  )
                  state += (sessionId -> updatedSS)
                  App ! AppData(sessionId, combinedData)
                case _ =>
                  val unackedChunks = (curr.recv.chunks + (position -> data)).filterKeys(p => p > curr.recv.acked)
                  val (combinedData, received, otherChunks) = combine(curr.recv.acked, unackedChunks)
                  val p = Ack(sessionId = sessionId, length = received)
                  val cs = context.system.scheduler.scheduleOnce(SessionExpiry)(closeSession(sessionId))
                  udpSender ! Udp.Send(ByteString(Writer(p)), curr.remote)
                  val updatedSS = curr.copy(
                    recv = curr.recv.copy(acked = received, recv = received, chunks = otherChunks),
                    lastAckSent = Some(p),
                    closeState = cs,
                  )
                  state += (sessionId -> updatedSS)
                  App ! AppData(sessionId, combinedData)
              }
            case None =>
              val p = Close(sessionId)
              udpSender ! Udp.Send(ByteString(Writer(p)), remote)
              state -= sessionId
          }


        case Some(Ack(sessionId, length)) =>
          state.get(sessionId) match {
            case Some(curr) =>
              curr.closeState.cancel
              (length <= curr.sent.buffer.length, length == curr.sent.buffer.length) match {
                case (true, false) =>
                  // Resend bytes
                  val resend = curr.sent.buffer.substring(length)
                  var pOpt: Option[Packet] = None
                  resend.grouped(MaxDataSizeBytes).zipWithIndex.foreach { case (subdata, ind) =>
                    pOpt = Some(Data(sessionId = sessionId, position = length + (MaxDataSizeBytes * ind), data = subdata))
                    pOpt.foreach(p => udpSender ! Udp.Send(ByteString(Writer(p)), curr.remote))
                  }
                  val cs = context.system.scheduler.scheduleOnce(SessionExpiry)(closeSession(sessionId))
                  state += (sessionId -> curr.copy(lastDataSent = pOpt, closeState = cs))
                case (true, true) =>
                  val cs = context.system.scheduler.scheduleOnce(SessionExpiry)(closeSession(sessionId))
                  state += (sessionId -> curr.copy(sent = curr.sent.copy(acked = curr.sent.buffer.length), closeState = cs))
                  // Duplicate ack if less than or correct ack if
                case (false, _) =>
                  // Misbehaving client
                  val p = Close(sessionId)
                  udpSender ! Udp.Send(ByteString(Writer(p)), remote)
                  state -= sessionId
              }
            case None =>
              val p = Close(sessionId)
              udpSender ! Udp.Send(ByteString(Writer(p)), remote)
              state -= sessionId
          }

        case Some(Close(sessionId)) =>
          state.get(sessionId) match {
            case Some(curr) =>
              val p = Close(curr.sessionId)
              udpSender ! Udp.Send(ByteString(Writer(p)), curr.remote)
              state -= sessionId
            case None =>
              val p = Close(sessionId)
              udpSender ! Udp.Send(ByteString(Writer(p)), remote)
              state -= sessionId
          }

        case u @ _ =>
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
  system.actorOf(Props(classOf[UdpServer], "0.0.0.0", 9091))
  println("Press enter to exit...")
  StdIn.readLine()
  system.terminate()
}
