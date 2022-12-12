import Network.DeserializationStatus
import akka.util.ByteString

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

object Network {

  def messageLength(code: Byte, strLength: Byte = 0, numRoads: Byte = 0) = {
    val nrInt = if (numRoads < 0) (1 << 8) - numRoads else numRoads
    val slInt = if (strLength < 0) (1 << 8) - strLength else strLength
    if (code == Code.Error) slInt + 2
    else if (code == Code.Plate) slInt + 6
    else if (code == Code.WantHeartbeat) 5
    else if (code == Code.Heartbeat) 1
    else if (code == Code.IAmCamera) 7
    else if (code == Code.Ticket) slInt + 18
    else if (code == Code.IAmDispatcher) 2 + (nrInt * 2)
    else 0
  }

  object Code {
    val Error: Byte = 0x10
    val Plate: Byte = 0x20
    val Ticket: Byte = 0x21
    val WantHeartbeat: Byte = 0x40
    val Heartbeat: Byte = 0x41
    val IAmCamera: Byte = -128
    val IAmDispatcher: Byte = -127
  }
  val ValidCodes = Set(
    Code.Error,
    Code.Plate,
    Code.Ticket,
    Code.WantHeartbeat,
    Code.Heartbeat,
    Code.IAmCamera,
    Code.IAmDispatcher
  )

  class Message(code: Byte)

  case class Error(
    code: Byte = Code.Error,
    msg: String
  ) extends Message(code)

  case class Plate(
    code: Byte = Code.Plate,
    msg: String,
    timestamp: Int
  ) extends Message(code)

  case class Ticket(
    code: Byte = Code.Ticket,
    plate: String,
    road: Short,
    mile1: Short,
    timestamp1: Int,
    mile2: Short,
    timestamp2: Int,
    speed: Short
  ) extends Message(code)

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