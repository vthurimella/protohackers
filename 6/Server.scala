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

object Time {
  def toDate(ts: Int): Int = ts / 86400
}

case class AddDispatcher(roads: Array[Short], ref: ActorRef)
case class RemoveDispatcher(ref:ActorRef)

class RoadToDispatcher extends Actor {
  var roadToDispatcher = Map.empty[Short, Array[ActorRef]]
  var unsentTickets = Array.empty[Network.Ticket]

  def sendTicket(ticket: Network.Ticket) = {
    roadToDispatcher.get(ticket.road) match {
      case None =>
        Some(ticket)
      case Some(ds) =>
        ds.headOption.foreach(d => d ! ticket)
        None
    }
  }

  def receive = {
    case ticket @ Network.Ticket(_, _, _, _, _, _, _, _) =>
      unsentTickets ++= sendTicket(ticket)
    case AddDispatcher(roads, ref) =>
      roads.foreach { r => roadToDispatcher += (r -> (roadToDispatcher.getOrElse(r, Array.empty) ++ Array(ref))) }
      unsentTickets = unsentTickets.flatMap(sendTicket)
    case RemoveDispatcher(ref) =>
      roadToDispatcher = roadToDispatcher.map { case (k, v) => (k, v.filterNot(_ == ref)) }
  }
}

case class Observation(
  plate: String,
  timestamp: Int,
  road: Short,
  limit: Short,
  mile: Short,
)

// Needs access to RoadToDispatcher
class ObservationAggregator(r2d: ActorRef) extends Actor {
  var obs: Array[Observation] = Array.empty[Observation]
  var sentDates: Set[Int] = Set.empty[Int]

  override def receive = {
    case o @ Observation(plate, ts, road, limit, mile) =>
      obs.foreach { prev =>
        val dx = mile - prev.mile
        val dt = (ts - prev.timestamp) / 3600.0
        val calcSpeed = math.abs(dx / dt)
        val (o1, o2) = prev.timestamp < ts match {
          case true => ((prev.timestamp, prev.mile), (ts, mile))
          case false => ((ts, mile), (prev.timestamp, prev.mile))
        }
        calcSpeed - limit > 0.5 match {
          case true =>
            val ticket = Network.Ticket(
              plate = plate,
              road = road,
              mile1 = o1._2,
              timestamp1 = o1._1,
              mile2 = o2._2,
              timestamp2 = o2._1,
              speed = (calcSpeed * 100).toShort,
            )
            sentDates.contains(Time.toDate(o1._1)) || sentDates.contains(Time.toDate(o2._1)) match {
              case true =>
                println(s"Not sending ${ticket} since another was already sent")
              case false =>
                r2d ! ticket
                (Time.toDate(o1._1) to Time.toDate(o2._1)).foreach(i => sentDates += i)
            }
          case false =>
        }
      }
      obs ++= Array(o)
  }
}

class ObservationProxy(r2d: ActorRef) extends Actor {
  var roadToCamera: Map[(String, Short), ActorRef] = Map.empty[(String, Short), ActorRef]

  def receive = {
    case o @ Observation(plate, ts, road, _, _) =>
      val out = roadToCamera.getOrElse(
        (plate, road),
        context.actorOf(Props(new ObservationAggregator(r2d)))
      )
      roadToCamera += ((plate, road) -> out)
      out ! o
  }
}

sealed abstract class ClientType
case object Undetermined extends ClientType
case object Camera extends ClientType
case object Dispatcher extends ClientType

case class ClientState(
  wantsHeartBeat: Boolean = false,
  clientType: ClientType = Undetermined,
  message: Option[Network.Message] = None,
  heartBeat: Option[Cancellable] = None,
)

case class Client(
  client: ActorRef,
  obsProxy: ActorRef,
  roadDispatch: ActorRef,
) extends Actor {
  var state = ClientState()
  var recvBuffer: Array[Byte] = Array.empty[Byte]
  def bytesToHex(bytes: Array[Byte]) = {
    bytes.map(b => Integer.toHexString(b.toInt & 0xFF)).mkString(" ")
  }
  def receive = {
    case t @ Network.Ticket(_, _, _, _, _, _, _, _) if state.clientType == Dispatcher =>
      val msg = ByteString(Network.serialize(t))
      client ! Write(msg)
    case t @ Network.Ticket(_, _, _, _, _, _, _, _) if state.clientType != Dispatcher =>
    case PeerClosed =>
      state.heartBeat.foreach(_.cancel())
      context.stop(self)
    case Received(data) =>
      val (nb, msgs, status) = Network.deserialize(recvBuffer, data.toArray)
      status match {
        case Network.UnknownMessageType =>
          val msg = ByteString(Network.serialize(Network.Error(msg = "unknown msg type")))
          client ! Write(msg)
          context.stop(self)
        case Network.Success =>
          recvBuffer = nb
          msgs.foreach {
            case p@Network.Plate(_, plate, timestamp) =>
              state.message match {
                case Some(Network.IAmCamera(_, road, mile, limit)) =>
                  val o = Observation(
                    plate = plate,
                    timestamp = timestamp,
                    road = road,
                    limit = limit,
                    mile = mile
                  )
                  obsProxy ! o
                case _ =>
                  val msg = ByteString(Network.serialize(Network.Error(msg = "Receive plate without declaring")))
                  client ! Write(msg)
                  context.stop(self)
              }
            case e@Network.Error(_, msg) =>
              val msg = ByteString(Network.serialize(Network.Error(msg = "client sent server msg")))
              client ! Write(msg)
              context.stop(self)
            // Send plate and road and timestamp to car observation store
            case iac@Network.IAmCamera(_, road, mile, limit) if state.clientType == Undetermined =>
              state = state.copy(clientType = Camera, message = Some(iac))
            case iac@Network.IAmCamera(_, road, mile, limit) if state.clientType != Undetermined =>
              val msg = ByteString(Network.serialize(Network.Error(msg = "duplicate IAmCamera message")))
              client ! Write(msg)
              context.stop(self)
            case iad@Network.IAmDispatcher(_, _, roads) if state.clientType == Undetermined =>
              state = state.copy(clientType = Dispatcher, message = Some(iad))
              roadDispatch ! AddDispatcher(roads, self)
            case iad@Network.IAmDispatcher(_, _, roads) if state.clientType != Undetermined =>
              val msg = ByteString(Network.serialize(Network.Error(msg = "duplicate IAmDispatcher message")))
              client ! Write(msg)
              context.stop(self)
            case Network.WantHeartbeat(_, interval) =>
              state.wantsHeartBeat match {
                case true =>
                  val msg = ByteString(Network.serialize(Network.Error(msg = "Already sent WantHeartbeat")))
                  client ! Write(msg)
                case false if interval != 0 =>
                  implicit val ec = context.system.dispatcher
                  val msg = ByteString(Network.serialize(Network.Heartbeat()))
                  val cancellable = context.system
                    .scheduler
                    .scheduleWithFixedDelay(Duration.Zero, (interval / 10.0).seconds, client, Write(msg))
                  state = state.copy(wantsHeartBeat = true, heartBeat = Some(cancellable))
                case false if interval == 0 =>
                  state = state.copy(wantsHeartBeat = true)
              }
          }
      }
  }
}

class TcpManager(val roadCamera: ActorRef, val roadDispatch: ActorRef) extends Actor {
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
      val handler = context.actorOf(Props(Client(connection, roadCamera, roadDispatch)))
      connection ! Register(handler)
  }
}

object Server extends App {
  implicit val system: ActorSystem = ActorSystem("Server")
  val disRef = system.actorOf(Props(new RoadToDispatcher))
  val obsProxyRef = system.actorOf(Props(new ObservationProxy(disRef)))
  system.actorOf(Props(new TcpManager(obsProxyRef, disRef)))
  println("Press enter to exit...")
  StdIn.readLine()
  system.terminate()
}
