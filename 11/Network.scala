import Network.DeserializationStatus
import akka.util.ByteString

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

object Network {

  object Code {
    val Hello: Byte = 0x50
    val Error: Byte = 0x51
    val OK: Byte = 0x52
    val DialAuthority: Byte = 0x53
    val TargetPopulations: Byte = 0x54
    val CreatePolicy: Byte = 0x55
    val DeletePolicy: Byte = 0x56
    val PolicyResult: Byte = 0x57
    val SiteVisit: Byte = 0x58
  }

  val ValidCodes = Set(
    Code.Hello,
    Code.Error,
    Code.OK,
    Code.DialAuthority,
    Code.TargetPopulations,
    Code.CreatePolicy,
    Code.DeletePolicy,
    Code.PolicyResult,
    Code.SiteVisit
  )

  trait Message {
    val code: Byte
    val length: Int
    val checksum: Byte
  }

  case class Hello(
    code: Byte = Code.Hello,
    length: Int = 25, // 25 bytes
    protocol: String = "pestcontrol",
    version: Int = 1,
    checksum: Byte,
  ) extends Message

  case class Error(
    code: Byte = Code.Error,
    length: Int,
    message: String,
    checksum: Byte,
  ) extends Message

  case class OK(
    code: Byte = Code.OK,
    length: Int = 6, // 6 bytes
    checksum: Byte,
  ) extends Message

  case class DialAuthority(
    code: Byte = Code.DialAuthority,
    length: Int = 10, // 10 bytes
    site: Int,
    checksum: Byte
  ) extends Message

  case class Population(
    species: String,
    min: Int,
    max: Int,
  )

  case class TargetPopulations(
    code: Byte = Code.TargetPopulations,
    length: Int,
    site: Int,
    populations: Array[Population],
    checksum: Byte,
  ) extends Message

  case class WantHeartbeat(
    code: Byte = Code.WantHeartbeat,
    interval: Int,
  ) extends Message(code)

  case class Heartbeat(
    code: Byte = Code.Heartbeat,
  ) extends Message(code)

  case class IAmCamera(
    code: Byte = Code.IAmCamera,
    road: Short,
    mile: Short,
    limit: Short
  ) extends Message(code)

  case class IAmDispatcher(
    code: Byte = Code.IAmDispatcher,
    numRoads: Byte,
    roads: Array[Short]
  ) extends Message(code)

  sealed abstract class DeserializationStatus
  case object Success extends DeserializationStatus
  case object UnknownMessageType extends DeserializationStatus

  def deserialize(prev: Array[Byte], byteArr: Array[Byte]): (Array[Byte], Seq[Message], DeserializationStatus) = {
    def readString(inputStream: DataInputStream) = {
      val length = inputStream.readByte()
      val sb = new StringBuilder
      for (i <- (0 until length)) {
        sb.append(inputStream.readByte().toChar)
      }
      sb.mkString
    }

    def bytesToHex(bytes: Array[Byte]) = {
      bytes.map(b => Integer.toHexString(b.toInt & 0xFF)).mkString(" ")
    }

    def extract(bArr: Array[Byte]): (Array[Byte], Seq[Message], DeserializationStatus) = bArr match {
      // Base Case
      case Array() =>
        (bArr, Seq.empty[Message], Success)

      // Handle Error message
      case Array(code) if code == Code.Error =>
        (bArr, Seq.empty[Message], Success)
      case Array(code, b@_*) if code == Code.Error =>
        val body = b.toArray
        val msgLen = messageLength(Code.Error, body(0))
        bArr.length < msgLen match {
          case true =>
            (bArr, Seq.empty[Message], Success)
          case false =>
            val (rest, msgs, status) = extract(bArr.slice(msgLen, bArr.length))
            val inputStream = new DataInputStream(new ByteArrayInputStream(body.slice(0, msgLen)))
            val error = Error(
              body(0),
              readString(inputStream),
            )
            inputStream.close
            (rest, msgs ++ Seq(error), status)
        }

      // Handle Plate message
      case Array(code) if code == Code.Plate =>
        (bArr, Seq.empty[Message], Success)
      case Array(code, b @ _*) if code == Code.Plate =>
        val body = b.toArray
        val msgLen = messageLength(Code.Plate, body(0))
        bArr.length < msgLen match {
          case true =>
            (bArr, Seq.empty[Message], Success)
          case false =>
            val (rest, msgs, status) = extract(bArr.slice(msgLen, bArr.length))
            val inputStream = new DataInputStream(new ByteArrayInputStream(body.slice(0, msgLen)))
            val plate = Plate(
              body(0),
              readString(inputStream),
              inputStream.readInt
            )
            inputStream.close
            (rest, msgs ++ Seq(plate), status)
        }

      // Handle IAmCamera message
      case Array(code, _*) if bArr.length < messageLength(Code.IAmCamera) && code == Code.IAmCamera =>
        (bArr, Seq.empty[Message], Success)
      case Array(code, b @ _*) if code == Code.IAmCamera =>
        val body = b.toArray
        val msgLen = messageLength(Code.IAmCamera)
        val (rest, msgs, status) = extract(bArr.slice(msgLen, bArr.length))
        val inputStream = new DataInputStream(new ByteArrayInputStream(body.slice(0, msgLen)))
        val iac = IAmCamera(
          code,
          inputStream.readShort,
          inputStream.readShort,
          inputStream.readShort
        )
        inputStream.close
        (rest, msgs ++ Seq(iac), status)

      // Handle IAmDispatcher
      case Array(code) if code == Code.IAmDispatcher =>
        (bArr, Seq.empty[Message], Success)
      case Array(code, b @ _*) if code == Code.IAmDispatcher =>
        val body = b.toArray
        val msgLen = messageLength(code = Code.IAmDispatcher, numRoads = body(0))
        bArr.length < msgLen match {
          case true =>
            (bArr, Seq.empty[Message], Success)
          case false =>
            val (rest, msgs, status) = extract(bArr.slice(msgLen, bArr.length))
            val inputStream = new DataInputStream(new ByteArrayInputStream(body.slice(0, msgLen)))
            val numRoads = inputStream.readByte
            val roads = (0 until numRoads).map(_ => inputStream.readShort).toArray
            val iad = IAmDispatcher(
              code,
              numRoads,
              roads
            )
            inputStream.close
            (rest, msgs ++ Seq(iad), status)
        }

      // Handle WantHeartbeat
      case Array(code, _*) if bArr.length < messageLength(Code.WantHeartbeat) && code == Code.WantHeartbeat =>
        (bArr, Seq.empty[Message], Success)
      case Array(code, b @ _*) if bArr.length >= messageLength(Code.WantHeartbeat) && code == Code.WantHeartbeat =>
        val body = b.toArray
        val msgLen = messageLength(Code.WantHeartbeat)
        val (rest, msgs, status) = extract(body.slice(msgLen + 1, bArr.length))
        val inputStream = new DataInputStream(new ByteArrayInputStream(body.slice(0, msgLen)))
        val wh = WantHeartbeat(code, inputStream.readInt)
        inputStream.close
        (rest, msgs ++ Seq(wh), status)

      case Array(code, _*) if ValidCodes.contains(code) =>
        (bArr, Seq.empty[Message], Success)
      case Array(code, _*) if !ValidCodes.contains(code) =>
        (bArr, Seq.empty[Message], UnknownMessageType)
    }

    extract(prev ++ byteArr)
  }

  def serialize(msg: Message) = {
    def writeString(msg: String, oos: DataOutputStream) = {
      oos.writeByte(msg.length.toByte)
      msg.foreach(ch => oos.writeByte((ch & 0xFF).toByte))
    }

    val stream: ByteArrayOutputStream = new ByteArrayOutputStream()
    val oos = new DataOutputStream(stream)
    msg match {
      case Error(code, msg) =>
        oos.writeByte(code)
        writeString(msg, oos)
      case Plate(code, msg, timestamp) =>
        oos.writeByte(code)
        writeString(msg, oos)
        oos.writeInt(timestamp)
      case Ticket(code, plate, road, mile1, timestamp1, mile2, timestamp2, speed) =>
        oos.writeByte(code)
        writeString(plate, oos)
        oos.writeShort(road)
        oos.writeShort(mile1)
        oos.writeInt(timestamp1)
        oos.writeShort(mile2)
        oos.writeInt(timestamp2)
        oos.writeShort(speed)
      case WantHeartbeat(code, interval) =>
        oos.writeByte(code)
        oos.writeInt(interval)
      case Heartbeat(code) =>
        oos.writeByte(code)
      case IAmDispatcher(code, numRoads, roads) =>
        oos.writeByte(code)
        oos.writeByte(numRoads)
        roads.foreach(r => oos.writeShort(r))
      case IAmCamera(code, road, mile, limit) =>
        oos.writeByte(code)
        oos.writeShort(road)
        oos.writeShort(mile)
        oos.writeShort(limit)
    }
    oos.close
    stream.toByteArray
  }
}