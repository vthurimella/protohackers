import akka.actor.{Actor, ActorRef}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class Job(id: String, queue: String, data: Map[String, Any], priority: Long)
object Job {
  implicit def byPriority = Ordering.by[Job, Long](_.priority)
}

case class PutJob(job: Job)
case class GetJob(queues: Seq[String], shouldWait: Boolean, client: ActorRef)
case class DeleteJob(jobId: String, client: ActorRef)
case class AbortJob(jobId: String, client: ActorRef)
case class AbortAllJobs(client: ActorRef)

class JobManager extends Actor {
  import Job._
  var waitingRequest = ListBuffer.empty[GetJob]
  var jobQueues = Map.empty[String, mutable.PriorityQueue[Job]]
  var activeJobs = Map.empty[String, (Job, ActorRef)]
  var idToQueue = Map.empty[String, String]

  def putJob(job: Job): Unit =
    waitingRequest.find(_.queues.contains(job.queue)) match {
      case Some(getJobReq) =>
        val resp = GetResp(
          id = job.id,
          priority = job.priority,
          queue = job.queue,
          job = job.data
        )
        getJobReq.client ! resp
        waitingRequest -= getJobReq
        activeJobs += (job.id -> (job, getJobReq.client))
      case None =>
        val pq = jobQueues.getOrElse(job.queue, mutable.PriorityQueue[Job]())
        pq.enqueue(job)
        jobQueues += (job.queue -> pq)
        idToQueue += (job.id -> job.queue)
    }

  def getHighestPriorityJob(queues: Seq[String]): Option[(String, Job)] =
    queues.foldLeft (Option.empty[(String, Job)]) { case (curr, q) =>
      val potJob = jobQueues.getOrElse(q, mutable.PriorityQueue.empty[Job])
        .headOption
      (curr, potJob) match {
        case (_, None) =>
          curr
        case (None, Some(job)) =>
          Some((q, job))
        case (Some((_, currJob)), Some(job)) if job.priority > currJob.priority =>
          Some((q, job))
        case (Some(tup), _) =>
          Some(tup)
        case _ =>
          None
      }
    }

  override def receive: Receive = {
    case PutJob(job) =>
      putJob(job)
    case gj @ GetJob(queues, shouldWait, client) =>
      getHighestPriorityJob(queues) match {
        case None if shouldWait =>
          waitingRequest += gj
        case None if !shouldWait =>
          client ! NoJob()
        case Some((queue, job)) =>
          val resp = GetResp(
            id = job.id,
            priority = job.priority,
            queue = queue,
            job = job.data
          )
          client ! resp
          activeJobs += (job.id -> (job, client))
          jobQueues.get(queue).foreach(_.dequeue)
      }
    case DeleteJob(jobId, client) =>
      activeJobs.get(jobId) match {
        case Some(_) =>
          activeJobs -= jobId
          idToQueue -= jobId
          client ! DeleteResp()
        case None =>
          idToQueue.get(jobId) match {
            case Some(queueKey) =>
              val rmPQ = jobQueues.get(queueKey)
                .map(_.filter(_.id != jobId))
                .getOrElse(mutable.PriorityQueue.empty[Job])
              jobQueues += (queueKey -> rmPQ)
              idToQueue -= jobId
              client ! DeleteResp()
            case None =>
              client ! NoJob()
          }
      }

    case AbortJob(jobId, client) =>
      activeJobs.get(jobId) match {
        case Some((job, workingClient)) if client == workingClient =>
          activeJobs -= jobId
          putJob(job)
          client ! AbortResp()
        case Some((_, workingClient)) if client != workingClient =>
          client ! ErrorResp("Active job aborted by other client")
        case None =>
          client ! NoJob()
      }

    case AbortAllJobs(client) =>
      activeJobs.filter { case (_, (_, workingClient)) => workingClient == client }
        .foreach { case (jobId, (job, _)) =>
          activeJobs -= jobId
          putJob(job)
        }
      waitingRequest = waitingRequest.filter(_.client != client)
  }
}