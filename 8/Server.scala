import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Tcp.{PeerClosed, Received}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import java.net.InetSocketAddress
import scala.io.StdIn

case class ApplicationReceive(msg: String)
case class ApplicationSend(msg: String)

class Application(client: ActorRef) extends Actor {
  var prevData = ""

  def processLine(line: String) =
    line.split(",")
      .map(toyStr => (toyStr, toyStr.split(" ").headOption))
      .map { case (toyStr, countOpt) => (toyStr, countOpt.map(c => c.replace("x", "").toInt)) }
      .maxBy { case (_, cOpt) => cOpt.getOrElse(0) }
      ._1

  override def receive: Receive = {
    case ApplicationReceive(msg) =>
      val allLines = (prevData + msg).split("\n", -1)
      val toProcess = allLines.dropRight(1)
      prevData = allLines.takeRight(1)(0)
      toProcess.foreach { line =>
        client ! ApplicationSend(processLine(line) + "\n")
      }
  }
}

trait TransformByte extends ((Int, Int) => Int)

trait TransformPair {
  def encrypt: TransformByte
  def decrypt: TransformByte
}

class ReverseBits extends TransformPair {
  override def encrypt: TransformByte = { case (input, _) =>
    (0 until 8).map(ind => (input >> ind) & 1)
      .reverse
      .zipWithIndex
      .foldLeft(0) { case (res, (bit, ind)) => res | (bit << ind) }
  }
  override def decrypt: TransformByte = encrypt
}

class Xor(val N: Int) extends TransformPair {
  override def encrypt: TransformByte = { case (input, _) => input ^ N }
  override def decrypt: TransformByte = encrypt
}

class XorPos extends TransformPair {
  override def encrypt: TransformByte = { case (input, pos) => input ^ pos }
  override def decrypt: TransformByte = encrypt
}

class Add(val N: Int) extends TransformPair {
  val MaxByte = 256
  override def encrypt: TransformByte = { case (input, _) => (input + N) % MaxByte }
  override def decrypt: TransformByte = { case (input, _) => (input - N + MaxByte) % MaxByte }
}

class AddPos extends TransformPair {
  val MaxByte = 256
  override def encrypt: TransformByte = { case (input, pos) => (input + pos) % MaxByte }
  override def decrypt: TransformByte = { case (input, pos) => (input - pos + MaxByte) % MaxByte }
}

object Obfuscater {
  val End = 0
  val ReverseBits = 1
  val Xor = 2
  val XorPos = 3
  val Add = 4
  val AddPos = 5

  def validTransformations(trans: Seq[TransformPair]): Boolean =
    (0 to 255)
      .zipWithIndex
      .map { case (v, ind) => (trans.foldLeft(v) { case (res, t) => t.encrypt(res, 0) }, ind) }
      .exists { case (enc, ind) => enc != ind }

  def transformPair(b1: Int, b2: Option[Int] = None) = b1 match {
    case ReverseBits => new ReverseBits
    case Xor => new Xor(b2.get)
    case XorPos => new XorPos
    case Add => new Add(b2.get)
    case AddPos => new AddPos
  }
}

sealed abstract class ClientState
case object Obfuscation extends ClientState
case object Data extends ClientState
case object Error extends ClientState

class ClientHandler(connection: ActorRef) extends Actor {
  import Obfuscater._

  val MaxUnsignedByte = 255

  var state: ClientState = Obfuscation
  var transforms: Seq[TransformPair] = Nil
  var prevOpt: Option[Int] = None
  var unprocessed: ByteString = ByteString.empty
  var validEncryption: Boolean = false
  var recvPos = 0
  var sendPos = 0
  val App = context.actorOf(Props(new Application(self)))

  def receive = {
    case ApplicationSend(msg) =>
      val byteArr = msg.getBytes
      for (i <- 0 until byteArr.length) {
        byteArr(i) = (transforms.foldLeft(byteArr(i).toInt) { case (res, t) =>
          t.encrypt(res, (sendPos + i) & MaxUnsignedByte)
        } & MaxUnsignedByte).toByte
      }
      connection ! Tcp.Write(ByteString(byteArr))
      sendPos += byteArr.length
      sendPos &= MaxUnsignedByte
    case Received(data) =>
      state match {
        case Obfuscation =>
          var i = 0
          while (i < data.length && state == Obfuscation) {
            prevOpt match {
              case Some(prev) =>
                transforms ++= Seq(transformPair(prev, Some(data(i) & 255)))
                prevOpt = None
              case None =>
                data(i) match {
                  case End =>
                    state = Data
                    validEncryption = validTransformations(transforms)
                  case Xor | Add =>
                    prevOpt = Some(data(i))
                  case ReverseBits | XorPos | AddPos =>
                    transforms ++= Seq(transformPair(data(i)))
                  case _ =>
                    state = Error
                }
            }
            i += 1
          }
          unprocessed = data.drop(i)
        case Data if !validEncryption =>
          println("Closing because received not valid encryption scheme")
          context.stop(self)
        case Data if validEncryption =>
          val toProcess = Array.concat(unprocessed.toArray, data.toArray)
          var datagram = ""
          for (i <- 0 until toProcess.length) {
            datagram += transforms.foldRight (toProcess(i).toInt) { case (t, res) =>
              t.decrypt(res, (recvPos + i) & MaxUnsignedByte)
            }.toChar
          }
          App ! ApplicationReceive(datagram)
          recvPos += toProcess.length
          recvPos &= MaxUnsignedByte
          unprocessed = ByteString.empty
        case Error =>
          println("Received unparsable obfuscation byte")
          context.stop(self)
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
      val handler = context.actorOf(Props(new ClientHandler(connection)))
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
