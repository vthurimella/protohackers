import akka.actor.{Actor, ActorRef}

import scala.collection.mutable

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
    case FileSystem.Put(file, _, data, requester) =>
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
              // Same file no need to update
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