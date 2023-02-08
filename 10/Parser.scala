import scala.util.{Failure, Success, Try}

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
        Failure(Errors.IllegalMethod(""))
    }
  }
}