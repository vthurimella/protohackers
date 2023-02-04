import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Tcp.{Closed, PeerClosed, Received, Write}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonProperty}
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.io.StringWriter
import java.net.InetSocketAddress
import scala.io.StdIn
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}


object FileSystem {

  class Command(requester: ActorRef)
  case class Get(file: String, revision: Option[Int], requester: ActorRef) extends Command(requester)
  case class Put(file: String, length: Int, data: String, requester: ActorRef) extends Command(requester)
  case class List(dir: String, requester: ActorRef) extends Command(requester)
}

trait FileSystemNode
case class Directory(
  name: String,
  directories: Map[String, Directory],
  files: Map[String, File]
) extends FileSystemNode
case class File(
  name: String,
  currentRevision: Int,
  archive: Map[Int, String]
) extends FileSystemNode

class FileSystem extends Actor {

private var root = Directory(
  "/",
  Map.empty[String, Directory],
  Map.empty[String, File]
)

  def findFolder(path: Array[String]): Option[Directory] =
    path.foldLeft(Option(root)) {
      case (Some(dir), name) =>
        dir.directories.get(name)
      case (None, _) =>
        None
    }


  def receive = {
    case FileSystem.List(dir, requester) =>
      (dir.startsWith("/"), findFolder(dir.split("/").filter(_.nonEmpty))) match {
        case (false, _) =>
          // TODO: Return error to the client
        case (true, Some(folder)) =>
          requester ! Client.Success.List((folder.files.values ++ folder.directories.values).toSeq)
        case (true, None) =>
          requester ! Client.Success.List(Nil)
      }
  }
}


case class ExitableError(msg: String) extends Throwable(msg)

trait Command
// ERR usage: GET file [revision]
case class Get(file: String, revision: Option[Int]) extends Command
// ERR usage: PUT file length newline data
case class Put(file: String, length: Int) extends Command
// ERR usage: LIST dir
case class List(dir: String) extends Command
// ERR usage: HELP
case object Help extends Command


object Parser {
  object CommandLabels {
    val Get = "GET"
    val Put = "PUT"
    val List = "LIST"
    val Help = "HELP"
  }

  object Errors {
    val GetUsage = new Exception("ERR usage: GET file [revision]")
    val PutUsage = new Exception("ERR usage: PUT file length newline data")
    val ListUsage = new Exception("ERR usage: LIST dir")
    def IllegalMethod(method: String) = ExitableError(s"ERR illegal method: $method")
  }
  def apply(line: String): Try[Command] = {
    val tokens = line.split(" ")
    tokens.headOption match {
      case Some(CommandLabels.Get) =>
        tokens.tail match {
          case Array(file) => Success(Get(file, None))
          case Array(file, revision) => Success(Get(file, Some(revision.toInt)))
          case _ => Failure(Errors.GetUsage)
        }

      case Some(CommandLabels.Put) =>
        tokens.tail match {
          case Array(file, length) => Success(Put(file, length.toInt))
          case _ => Failure(Errors.PutUsage)
        }

      case Some(CommandLabels.List) =>
        tokens.tail match {
          case Array(dir) => Success(List(dir))
          case _ => Failure(Errors.ListUsage)
        }

      case Some(CommandLabels.Help) =>
        Success(Help)

      case Some(unknownCommand) =>
        Failure(Errors.IllegalMethod(unknownCommand))

      case None =>
        // TODO: should this be an error?
        Failure(Errors.IllegalMethod(""))
    }
  }
}

object Client {
  val HelpMessage = "OK usage: HELP|GET|PUT|LIST"
  val ReadyMessage = "READY"

  trait FileSystemResponses

  object Success {
    case class Put(revision: Int) extends FileSystemResponses
    case class Get(data: String) extends FileSystemResponses
    case class List(files: Seq[FileSystemNode]) extends FileSystemResponses
  }
}

class Client(val client: ActorRef, fileSystem: ActorRef) extends Actor {
  private var buffer: String = ""
  private var putCmd: Option[Put] = None

  private def handlePut(cmd: Put): Unit =
    cmd.length <= buffer.length match {
      case true =>
        val (data, rest) = buffer.splitAt(cmd.length)
        putCmd = None
        fileSystem ! FileSystem.Put(cmd.file, cmd.length, data, self)
        buffer = rest
      case false =>
        putCmd = Some(cmd)
    }

  client ! Write(ByteString(Client.ReadyMessage + "\n"))

  def receive = {
    case Client.Success.Get(data) =>
      client ! Write(ByteString(s"OK ${data.length}\n${data}\n${Client.ReadyMessage}\n"))

    case Client.Success.Put(revision) =>
      client ! Write(ByteString(s"OK r${revision}\n${Client.ReadyMessage}\n"))

    case Client.Success.List(files) =>
      val fileStrs = files.map {
        case Directory(name, _, _) => s"$name/ DIR"
        case File(name, revision, _) => s"$name r$revision"
      }
      val serialized = fileStrs match {
        case Nil => ""
        case _ => fileStrs.mkString("\n")
      }
      client ! Write(ByteString(s"OK ${files.length}\n${serialized}${Client.ReadyMessage}\n"))

    case Received(data) =>
      buffer += data.utf8String
      putCmd match {
        case Some(p: Put) =>
          handlePut(p)
        case None =>
          buffer.contains('\n') match {
            case false =>
              ()
            case true =>
              val (line, rest) = buffer.span(_ != '\n')
              buffer = rest.tail
              Parser(line) match {
                case Success(Help) =>
                  client ! Write(ByteString(s"${Client.HelpMessage}\n${Client.ReadyMessage}\n"))
                case Success(p: Put) =>
                  handlePut(p)
                case Success(Get(file, revision)) =>
                  println(s"Received ${Get(file, revision)}")
                  fileSystem ! FileSystem.Get(file, revision, self)
                case Success(List(dir)) =>
                  fileSystem ! FileSystem.List(dir, self)
                case Failure(err: ExitableError) =>
                  client ! Write(ByteString(s"${err.getMessage}\n"))
                  context.stop(self)
                case Failure(err) =>
                  client ! Write(ByteString(s"${err.getMessage}\n"))
                case unknown =>
                  println(s"Line parse failed and parsed ${unknown.getClass}")
              }
          }
      }
    case PeerClosed =>
      context.stop(self)
  }
}


class TcpManager(fileSystem: ActorRef) extends Actor {
  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 9091))

  def receive = {
    case b @ Bound(_) =>
      context.parent ! b

    case CommandFailed(_: Bind) =>
      context.stop(self)

    case Connected(_, _) =>
      val connection = sender()
      val handler = context.actorOf(Props(new Client(connection, fileSystem)))
      connection ! Register(handler)
  }
}


object Server extends App {
  implicit val system: ActorSystem = ActorSystem("Server")
  val fileSystem = system.actorOf(Props(new FileSystem))
  system.actorOf(Props(new TcpManager(fileSystem)))
  println("Press enter to exit...")
  StdIn.readLine()
  system.terminate()
}
