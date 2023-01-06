import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonProperty}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import java.io.StringWriter
import scala.reflect.ClassTag
import scala.util.Try

object Json {
  def parse[T: ClassTag](json: String)(implicit ct: ClassTag[T]) = {
    val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
    Try(mapper.readValue(json, ct.runtimeClass))
  }

  def toString[T](obj: T) = {
    val out = new StringWriter()
    val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
    mapper.writeValue(out, obj)
    out.toString()
  }
}

object Response {
  case class Base(status: String)
  case class Put(status: String = "ok", id: String)
  case class Get(status: String = "ok", id: String, job: Map[String, Any], pri: Long, queue: String)
  case class Error(status: String = "error", error: Option[String] = None)

  val NoJob = Base("no-job")
  val OkOp = Base("ok")
}

object Request {
  @JsonIgnoreProperties(ignoreUnknown = true)
  case class Type(request: String)

  @JsonIgnoreProperties(ignoreUnknown = true)
  case class Put(queue: String, job: Map[String, Any], pri: Long)

  @JsonIgnoreProperties(ignoreUnknown = true)
  case class Get(
    queues: Seq[String],
    @JsonProperty(value = "wait", required = false)
    shouldWait: Boolean
  )

  @JsonIgnoreProperties(ignoreUnknown = true)
  case class Delete(id: String)

  @JsonIgnoreProperties(ignoreUnknown = true)
  case class Abort(id: String)


  val GetType = Type("get")
  val PutType = Type("put")
  val DeleteType = Type("delete")
  val AbortType = Type("abort")
}