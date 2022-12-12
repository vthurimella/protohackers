
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

object Json {
  def parse[T: ClassTag](json: String)(implicit ct: ClassTag[T]) = {
    val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
    mapper.readValue(json, ct.runtimeClass)
  }

  def toString[T](obj: T) = {
    val out = new StringWriter()
    val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
    mapper.writeValue(out, obj)
    out.toString()
  }
}

class NumberDeserializer extends JsonDeserializer[Double] {
  override def deserialize(jp: JsonParser, ctx: DeserializationContext): Double = {
    jp.getDoubleValue
  }
}

case class Response(method: String = "isPrime", prime: Boolean)

@JsonIgnoreProperties(ignoreUnknown = true)
case class Request(
  method: String,
  @JsonProperty(required = true)
  @JsonDeserialize(using = classOf[NumberDeserializer])
  number: Double
)

class SimplisticHandler extends Actor {
  var buffer: String = ""

  def isPrime(num: Double): Boolean = {
    val numInt = num.toInt
    numInt == num && numInt >= 2 && (2 to Math.sqrt(numInt).toInt).forall(numInt % _ != 0)
  }
  def respondAndClose(senderRef: ActorRef) = {
    senderRef ! Closed
    context.stop(self)
  }

  def receive = {
    case Received(data) =>
      val strData = data.utf8String
      if (!strData.contains('\n')) {
        buffer = strData
      } else {
        val appendedData =
          if (buffer.length > 0) {
            buffer + strData
          } else
            strData

        appendedData.split('\n').map { req =>
          val respOpt = for {
            Request(method, number) <- Try(Json.parse[Request](req)).toOption
            jsonRespStr = s"${Json.toString[Response](Response(prime = isPrime(number)))}\n"
            respStr <- Option(ByteString(jsonRespStr)).filter(_ => method == "isPrime")
          } yield respStr

          respOpt match {
            case Some(resp) =>
              sender() ! Write(resp)
              buffer = ""
            case None =>
              respondAndClose(sender())
          }
        }
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
      val handler = context.actorOf(Props[SimplisticHandler]())
      val connection = sender()
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
