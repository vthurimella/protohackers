import Parser.Errors
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Tcp.{PeerClosed, Received, Write}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import scala.collection.mutable
import java.net.InetSocketAddress
import java.nio.charset.Charset
import scala.io.StdIn
import scala.util.{Failure, Success, Try}


object FileSystem {

  class Command(requester: ActorRef)
  case class Get(file: String, revision: Option[Int], requester: ActorRef) extends Command(requester)
  case class Put(file: String, length: Int, data: String, requester: ActorRef) extends Command(requester)
  case class List(dir: String, requester: ActorRef) extends Command(requester)
}

trait FileSystemNode {
  val name: String
}
case class Directory(
  name: String,
  directories: mutable.Map[String, Directory],
  files: mutable.Map[String, File]
) extends FileSystemNode
case class File(
  name: String,
  var currentRevision: Int = 0,
  archive: mutable.Map[Int, String] = mutable.Map.empty[Int, String]
) extends FileSystemNode

class FileSystem extends Actor {

private var root = Directory(
  "/",
  mutable.Map.empty[String, Directory],
  mutable.Map.empty[String, File]
)

  def findFolder(path: Array[String]): Option[Directory] =
    path.foldLeft(Option(root)) {
      case (Some(dir), name) =>
        dir.directories.get(name)
      case (None, _) =>
        None
    }


  def receive = {
    case FileSystem.Put(file, length, data, requester) =>
      val pathAndFile = file.split("/", -1)
      val (path, fileOpt) = (pathAndFile.dropRight(1).drop(1), pathAndFile.lastOption)
      val pwd = path.foldLeft(root) { case (curr, fdr) =>
        curr.directories.contains(fdr) match {
          case true =>
            curr.directories(fdr)
          case false =>
            val newDir = Directory(fdr, mutable.Map.empty[String, Directory], mutable.Map.empty[String, File])
            curr.directories += (fdr -> newDir)
            newDir
        }
      }
      fileOpt match {
        case None =>
          // Illegal file name should be filtered when parsing the command
        case Some(fileName) =>
          val file = pwd.files.getOrElse(fileName, File(fileName))
          val sameFile = file.archive
            .get(file.currentRevision)
            .exists(od => od == data)
          sameFile match {
            case true =>
              println("Same file not updating version")
            case false =>
              file.currentRevision += 1
              file.archive += (file.currentRevision -> data)
              pwd.files += (fileName -> file)
          }
          requester ! Client.Success.Put(file.currentRevision)
      }
    case FileSystem.Get(file, revision, requester) =>
      val pathAndFile = file.split("/", -1)
      val (path, fileOpt) = (pathAndFile.dropRight(1).drop(1), pathAndFile.lastOption)
      (findFolder(path), fileOpt) match {
        case (Some(_), None) =>
          // Not a valid path should be filtered when parsing the command
        case (None, _) =>
          requester ! Client.NoSuchFile()
        case (Some(folder), Some(fileName)) =>
          folder.files.get(fileName) match {
            case None =>
              requester ! Client.NoSuchFile()
            case Some(file) =>
              val rev = revision.getOrElse(file.currentRevision)
              file.archive.get(rev) match {
                case None =>
                  requester ! Client.NoSuchRevision()
                case Some(data) =>
                  requester ! Client.Success.Get(data)
              }
          }
      }
    case FileSystem.List(dir, requester) =>
      findFolder(dir.split("/").filter(_.nonEmpty)) match {
        case Some(folder) =>
          requester ! Client.Success.List((folder.files.values ++ folder.directories.values).toSeq)
        case None =>
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

    val InvalidDirName = new Exception("ERR illegal dir name")
    val InvalidFileName = new Exception("ERR illegal file name")
    val InvalidFileContent = new Exception("ERR text files only")
    def IllegalMethod(method: String) = ExitableError(s"ERR illegal method: $method")
  }

  def validDirName(name: String) = {
    val validSymbols = Set('.', '/', '-', '_')
    name.forall(ch => ch.isLetterOrDigit || validSymbols.contains(ch)) &&
      name.nonEmpty &&
      name.startsWith("/") &&
      !name.contains("//")
  }
  def validFileName(name: String) =
    validDirName(name) && !name.endsWith("/")

  def apply(line: String): Try[Command] = {
    val tokens = line.split(" ")
    tokens.map(_.toUpperCase).headOption match {
      case Some(CommandLabels.Get) =>
        tokens.tail match {
          case Array(file) if validFileName(file) =>
            Success(Get(file, None))
          case Array(file, revision) if validFileName(file) =>
            Try(revision.filter(_ != 'r').toInt) match {
              case Success(rev) => Success(Get(file, Some(rev)))
              case Failure(_) => Success(Get(file, Some(-1)))
            }
          case Array(_) =>
            Failure(Errors.InvalidFileName)
          case Array(_, _) =>
            Failure(Errors.InvalidFileName)
          case _ => Failure(Errors.GetUsage)
        }

      case Some(CommandLabels.Put) =>
        tokens.tail match {
          case Array(file, length) if validFileName(file) =>
            Success(Put(file, length.toInt))
          case Array(_, _) =>
            Failure(Errors.InvalidFileName)
          case _ => Failure(Errors.PutUsage)
        }

      case Some(CommandLabels.List) =>
        tokens.tail match {
          case Array(dir) if validDirName(dir) =>
            Success(List(dir))
          case Array(_) =>
            Failure(Errors.InvalidDirName)
          case _ =>
            Failure(Errors.ListUsage)
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

  trait Failure extends FileSystemResponses {
    val msg: String
  }
  case class NoSuchFile(msg: String = "ERR no such file") extends Failure
  case class NoSuchRevision(msg: String = "ERR no such revision") extends Failure

}

class Client(val client: ActorRef, fileSystem: ActorRef) extends Actor {
  private var buffer: String = ""
  private var putCmd: Option[Put] = None

  override def preStart(): Unit = {
    client ! Write(ByteString(Client.ReadyMessage + "\n"))
  }

  private def handlePut(cmd: Put): Unit = {
    def isAscii(data: String) = data.forall(ch => (0x20 <= ch && ch <= 0x7E) || ch == '\n' || ch == '\t')

    cmd.length <= buffer.length match {
      case true =>
        val (data, rest) = buffer.splitAt(cmd.length)
        putCmd = None
        isAscii(data) match {
          case true =>
            fileSystem ! FileSystem.Put(cmd.file, cmd.length, data, self)
          case false =>
            client ! Write(ByteString(s"${Errors.InvalidFileContent.getMessage}\n${Client.ReadyMessage}\n"))
        }
        buffer = rest
        self ! Received(ByteString.empty)
      case false =>
        putCmd = Some(cmd)
    }
  }

  def receive = {
    case failure: Client.Failure =>
      client ! Write(ByteString(s"${failure.msg}\n${Client.ReadyMessage}\n"))
    case Client.Success.Get(data) =>
      client ! Write(ByteString(s"OK ${data.length}\n${data}${Client.ReadyMessage}\n"))

    case Client.Success.Put(revision) =>
      client ! Write(ByteString(s"OK r${revision}\n${Client.ReadyMessage}\n"))

    case Client.Success.List(files) =>
      val fileStrs = files.sortBy(_.name).map {
        case Directory(name, _, _) => s"$name/ DIR"
        case File(name, revision, _) => s"$name r$revision"
      }
      val serialized = fileStrs match {
        case Nil => ""
        case _ => fileStrs.mkString("", "\n", "\n")
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
