import akka.actor.{Actor, ActorRef}
import akka.io.Tcp.{PeerClosed, Received, Write}
import akka.util.ByteString

import scala.util.{Failure, Success}

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
    def isAscii(data: String) = data.forall(ch => (' ' <= ch && ch <= '~') || ch == '\n' || ch == '\t')

    cmd.length <= buffer.length match {
      case true =>
        val (data, rest) = buffer.splitAt(cmd.length)
        putCmd = None
        isAscii(data) match {
          case true =>
            fileSystem ! FileSystem.Put(cmd.file, cmd.length, data, self)
          case false =>
            client ! Write(ByteString(s"${Parser.Errors.InvalidFileContent.getMessage}\n${Client.ReadyMessage}\n"))
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
              // Wait for new line to extract command
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
