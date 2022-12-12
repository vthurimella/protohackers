import java.util.regex.Pattern
import scala.concurrent.duration._

object LRCP {
  val MaxPacketSizeBytes = 1000
  val MaxDataSizeBytes = 900
  val RetransmissionTimeout = 3.seconds
  val SessionExpiry = 60.seconds

  class Packet(sessionId: Int)
  case class Connect(sessionId: Int) extends Packet(sessionId)
  case class Data(sessionId: Int, position: Int, data: String) extends Packet(sessionId)
  case class Ack(sessionId: Int, length: Int) extends Packet(sessionId)
  case class Close(sessionId: Int) extends Packet(sessionId)

  object Headers {
    val Connect = "/connect/([0-9]*)/".r
    val Data = "(?s)/data/([0-9]*)/([0-9]*)/(.*)/".r
    val Ack = "/ack/([0-9]*)/([0-9]*)/".r
    val Close = "/close/([0-9]*)/".r
  }

  object Writer {
    def apply(packet: Packet) = packet match {
      case Connect(sessionId) =>
        s"/connection/${sessionId}/"
      case Data(sId, pos, data) =>
        s"/data/${sId}/${pos}/${data.replace("\\", "\\\\").replace("/", "\\/")}/"
      case Ack(sessionId, length) =>
        s"/ack/${sessionId}/${length}/"
      case Close(sessionId) =>
        s"/close/${sessionId}/"
    }
  }

  object Parser {
    def apply(msg: String): Option[Packet] = msg.length <= MaxPacketSizeBytes match {
      case true =>
        msg match {
          case Headers.Connect(sessionId) =>
            Some(Connect(sessionId.toInt))
          case Headers.Data(sessionId, pos, data) =>
            data.split('/').length == data.split(Pattern.quote("\\/")).length match {
              case true =>
                val escaped = data.replace("\\\\", "\\").replace("\\/", "/")
                val p = Data(sessionId.toInt, position = pos.toInt, data = escaped)
                Some(p)
              case false =>
                None
            }
          case Headers.Ack(sessionId, len) =>
            Some(Ack(sessionId = sessionId.toInt, length = len.toInt))
          case Headers.Close(sessionId) =>
            Some(Close(sessionId.toInt))
          case _ =>
            None
        }
      case false =>
        None
    }
  }
}