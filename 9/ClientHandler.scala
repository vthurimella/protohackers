import akka.actor.{Actor, ActorRef}
import akka.io.Tcp
import akka.io.Tcp.{PeerClosed, Received}
import akka.util.ByteString

import java.util.UUID
import scala.util.{Failure, Success}

// ====== Messages from JobManager ======
case class GetResp(id: String, priority: Long, queue: String, job: Map[String, Any])
case class NoJob()
case class AbortResp()
case class DeleteResp()
case class ErrorResp(msg: String)

class ClientHandler(
  val client: ActorRef,
  val jobManager: ActorRef
) extends Actor {
  var buffer: String = ""

  def sendFailedJsonParse(methodType: String, payload: String) = {
    val err = Response.Error(error = Some(s"Failed to parse ${methodType} ${payload}"))
    val resp = Json.toString[Response.Error](err)
    client ! Tcp.Write(ByteString(s"${resp}\n"))
  }

  def receive = {
    // ====== Send to Client ========
    case DeleteResp() | AbortResp() =>
      val resp = Json.toString[Response.Base](Response.OkOp)
      client ! Tcp.Write(ByteString(s"${resp}\n"))
    case NoJob() =>
      val resp = Json.toString[Response.Base](Response.NoJob)
      client ! Tcp.Write(ByteString(s"${resp}\n"))
    case GetResp(id, priority, queue, job) =>
      val resp = Json.toString[Response.Get](Response.Get(id = id, job = job, pri = priority, queue = queue))
      client ! Tcp.Write(ByteString(s"${resp}\n"))
    case ErrorResp(msg) =>
      val resp = Json.toString[Response.Error](Response.Error(error = Some(msg)))
      client ! Tcp.Write(ByteString(s"${resp}\n"))

    // ====== Recv from Client ========
    case Received(data) =>
      val strData = data.utf8String
      val allLines = (buffer + strData).split("\n", -1)
      val toProcess = allLines.dropRight(1)
      buffer = allLines.takeRight(1)(0)
      toProcess.foreach { proc =>
        Json.parse[Request.Type](proc) match {
          case Success(Request.GetType) =>
            val req = Json.parse[Request.Get](proc)
            req match {
              case Success(parsedReq: Request.Get) =>
                val getJob = GetJob(
                  queues = parsedReq.queues,
                  shouldWait = parsedReq.shouldWait,
                  client = self
                )
                jobManager ! getJob
              case _ =>
                println(s"Failed to parse GET ${proc}")
                sendFailedJsonParse("GET", proc)
            }
          case Success(Request.PutType) =>
            val req = Json.parse[Request.Put](proc)
            req match {
              case Success(parsedReq: Request.Put) =>
                val jobId = UUID.randomUUID.toString
                val job = Job(jobId, parsedReq.queue, parsedReq.job, parsedReq.pri)
                jobManager ! PutJob(job)
                val resp = Json.toString[Response.Put](Response.Put(id = jobId))
                client ! Tcp.Write(ByteString(s"${resp}\n"))
              case _ =>
                println(s"Failed to parse PUT ${proc}")
                sendFailedJsonParse("PUT", proc)
            }
          case Success(Request.AbortType) =>
            val req = Json.parse[Request.Abort](proc)
            req match {
              case Success(parsedReq: Request.Abort) =>
                jobManager ! AbortJob(parsedReq.id, self)
              case _ =>
                println(s"Failed to parse ABORT ${proc}")
                sendFailedJsonParse("ABORT", proc)

            }
          case Success(Request.DeleteType) =>
            val req = Json.parse[Request.Delete](proc)
            req match {
              case Success(parsedReq: Request.Delete) =>
                jobManager ! DeleteJob(parsedReq.id, self)
              case _ =>
                println(s"Failed to parse DELETE ${proc}")
                sendFailedJsonParse("DELETE", proc)
            }
          case Success(_) | Failure(_) =>
            println(s"Failed to parse UNKNOWN ${proc}")
            sendFailedJsonParse("UNKNOWN", proc)
        }
      }

    // ====== Connection Closed by Client ========
    case PeerClosed =>
      jobManager ! AbortAllJobs(self)
      context.stop(self)
  }
}