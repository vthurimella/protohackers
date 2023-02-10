import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

object Network {

  implicit class PopulationCountByteSum(val pc: PopulationCount) extends AnyVal {
    def byteSum: Int = {
      val start = pc.species.length.byteSum + pc.count.byteSum
      pc.species.getBytes.foldLeft (start) { case (acc, b) =>  acc + b }
    }
  }

  implicit class PopulationByteSum(val p: Population) extends AnyVal {
    def byteSum: Int = {
      val start = p.species.length.byteSum + p.min.byteSum + p.max.byteSum
      p.species.getBytes.foldLeft (start) { case (acc, b) => acc + b }
    }
  }

  implicit class StringByteSum(val s: String) extends AnyVal {
    def byteSum: Int = s.getBytes.foldLeft (s.length.byteSum) { case (acc, b) => acc + b }
  }

  implicit class IntByteSum(val i: Int) extends AnyVal {
    def byteSum: Int = (i & 0xFF) + ((i >> 8) & 0xFF) + ((i >> 16) & 0xFF) + ((i >> 24) & 0xFF)
  }

  object Code {
    val Hello: Byte = 0x50
    val Error: Byte = 0x51
    val OK: Byte = 0x52
    val DialAuthority: Byte = 0x53
    val TargetPopulations: Byte = 0x54
    val CreatePolicy: Byte = 0x55
    val DeletePolicy: Byte = 0x56
    val PolicyResult: Byte = 0x57
    val SiteVisit: Byte = 0x58
  }

  val ValidCodes = Set(
    Code.Hello,
    Code.Error,
    Code.OK,
    Code.DialAuthority,
    Code.TargetPopulations,
    Code.CreatePolicy,
    Code.DeletePolicy,
    Code.PolicyResult,
    Code.SiteVisit
  )

  trait Message {
    val code: Byte
    val length: Int
    val checksum: Byte
  }

  object Hello {
    val length = 25
    val protocol = "pestcontrol"
    val version = 1
    val checksum: Byte = -50
  }

  case class Hello(
    code: Byte = Code.Hello,
    length: Int = Hello.length, // 25 bytes
    protocol: String = "pestcontrol",
    version: Int = 1,
    checksum: Byte = -50, // 0xCE
  ) extends Message

  case class Error(
    code: Byte = Code.Error,
    message: String,
  ) extends Message {
    val length = 6 + message.length // 6 since message prefix is 4 bytes and code and checksum are 1 byte each
    val checksum = {
      val tot = message.getBytes.foldLeft (code + message.length.byteSum) { case (acc, b) =>
        acc + b
      }
      (tot % 256).toByte
    }
  }

  case class OK(
    code: Byte = Code.OK,
    length: Int = 6, // 6 bytes
    checksum: Byte = -88, // 0xA8
  ) extends Message

  case class DialAuthority(
    code: Byte = Code.DialAuthority,
    length: Int = 10, // 10 bytes
    site: Int,
  ) extends Message {
    val checksum = {
      val tot = code + length.byteSum + site.byteSum
      (tot % 256).toByte
    }
  }

  case class Population(
    species: String,
    min: Int,
    max: Int,
  )

  case class TargetPopulations(
    code: Byte = Code.TargetPopulations,
    site: Int,
    populations: Array[Population],
  ) extends Message {
    val length = 1 + 4 + 4 + populations.map(p => 4 + p.species.length + 4 + 4).sum + 1
    val checksum = {
      val tot = code + site.byteSum +
        populations.length.byteSum + populations.map(p => p.byteSum).sum +
        length.byteSum
      (tot % 256).toByte
    }
  }

  object PopulationAction {
    def apply(code: Byte): PopulationAction = code match {
      case -112 => Cull()
      case -96 => Conserve()
    }
  }

  sealed trait PopulationAction {
    val code: Byte
  }
  case class Cull(code: Byte = -112) extends PopulationAction
  case class Conserve(code: Byte = -96) extends PopulationAction

  case class CreatePolicy(
    code: Byte = Code.CreatePolicy,
    species: String,
    action: PopulationAction
  ) extends Message {
    val length = 1 + 4 + species.length + 1 + 1
    val checksum = {
      val tot = code + species.byteSum + action.code + length.byteSum
      (tot % 256).toByte
    }
  }

  case class DeletePolicy(
    code: Byte = Code.DeletePolicy,
    length: Int = 10, // 10 bytes,
    policy: Int,
  ) extends Message {
    val checksum = {
      val tot = code + length.byteSum + policy.byteSum
      (tot % 256).toByte
    }
  }

  case class PolicyResult(
    code: Byte = Code.PolicyResult,
    length: Int = 10, // 10 bytes
    policy: Int
  ) extends Message {
    val checksum = {
      val tot = code + length.byteSum + policy.byteSum
      (tot % 256).toByte
    }
  }

  case class PopulationCount(
    species: String,
    count: Int,
  )

  case class SiteVisit(
    code: Byte = Code.SiteVisit,
    site: Int,
    populations: Seq[PopulationCount]
  ) extends Message {
    val length = 1 + 4 + 4 + populations.map(p => 4 + p.species.length + 4).sum + 1
    val checksum: Byte = {
      val tot = code + site.byteSum +
        populations.length.byteSum + populations.map(p => p.byteSum).sum +
        length.byteSum
      (tot % 256).toByte
    }
  }

  sealed abstract class DeserializationStatus
  case object Success extends DeserializationStatus
  case object InvalidHello extends DeserializationStatus
  case object InvalidChecksum extends DeserializationStatus
  case object UnknownMessageType extends DeserializationStatus

  def deserialize(prev: Array[Byte], bArr: Array[Byte]): (Array[Byte], Seq[Message], DeserializationStatus) = {
    def readString(inputStream: DataInputStream) = {
      val length = inputStream.readInt
      val sb = new StringBuilder
      for (i <- (0 until length)) {
        sb.append(inputStream.readByte().toChar)
      }
      sb.mkString
    }

    def checkChecksum(arr: Array[Byte], length: Int) = {
      val sum = arr.take(length).foldLeft(0)((acc, b) => (acc + b))
      sum % 256 == 0
    }

    def bytesToHex(bytes: Array[Byte]) = {
      bytes.map(b => Integer.toHexString(b.toInt & 0xFF)).mkString(" ")
    }

    def extract(bArr: Array[Byte]): (Array[Byte], Seq[Message], DeserializationStatus) =
      bArr match {
        // Base Case
        case _ if bArr.length <= 5 =>
          (bArr, Seq.empty[Message], Success)

        case _ =>
          val inputStream = new DataInputStream(new ByteArrayInputStream(bArr.toArray))
          val code = inputStream.readByte
          val length = inputStream.readInt
          code match {
            // Hello Case
            case Code.Hello if length <= bArr.length =>
              val protocol = readString(inputStream)
              val version = inputStream.readInt
              val checksum = inputStream.readByte
              (protocol == Hello.protocol && version == Hello.version, checksum == Hello.checksum) match {
                case (true, true) =>
                  val (remaining, messages, status) = extract(inputStream.readAllBytes)
                  (remaining, Hello() +: messages, status)
                case (true, false) =>
                  (bArr, Seq.empty[Message], InvalidChecksum)
                case _ =>
                  (bArr, Seq.empty[Message], InvalidHello)
              }
            case Code.Hello =>
              (bArr, Seq.empty[Message], Success)

            case Code.Error if length <= bArr.length =>
              val message = readString(inputStream)
              val checksum = inputStream.readByte
              checkChecksum(bArr, length) match {
                case true =>
                  val (remaining, messages, status) = extract(inputStream.readAllBytes)
                  (remaining, Error(message = message) +: messages, status)
                case false =>
                  (bArr, Seq.empty[Message], InvalidChecksum)
              }
            case Code.Error =>
              (bArr, Seq.empty[Message], Success)

            case Code.OK if length <= bArr.length =>
              val checksum = inputStream.readByte
              checkChecksum(bArr, length) match {
                case true =>
                  val (remaining, messages, status) = extract(inputStream.readAllBytes)
                  (remaining, OK(length = length, checksum = checksum) +: messages, status)
                case false =>
                  (bArr, Seq.empty[Message], InvalidChecksum)
              }
            case Code.OK =>
              (bArr, Seq.empty[Message], Success)

            case Code.DialAuthority if length <= bArr.length =>
              val site = inputStream.readInt
              val checksum = inputStream.readByte
              checkChecksum(bArr, length) match {
                case true =>
                  val (remaining, messages, status) = extract(inputStream.readAllBytes)
                  (remaining, DialAuthority(length = length, site = site) +: messages, status)
                case false =>
                  (bArr, Seq.empty[Message], InvalidChecksum)
              }
            case Code.DialAuthority =>
              (bArr, Seq.empty[Message], Success)

            case Code.TargetPopulations if length <= bArr.length =>
              val site = inputStream.readInt
              val populationLength = inputStream.readInt
              val populations = (0 until populationLength).map { _ =>
                val species = readString(inputStream)
                val min = inputStream.readInt
                val max = inputStream.readInt
                Population(species = species, min = min, max = max)
              }.toArray
              val checksum = inputStream.readByte
              checkChecksum(bArr, length) match {
                case true =>
                  val (remaining, messages, status) = extract(inputStream.readAllBytes)
                  (
                    remaining,
                    TargetPopulations(
                      site = site,
                      populations = populations,
                    ) +: messages,
                    status
                  )
                case false =>
                  (bArr, Seq.empty[Message], InvalidChecksum)
              }

            case Code.TargetPopulations =>
              (bArr, Seq.empty[Message], Success)

            case Code.CreatePolicy if length <= bArr.length =>
              val species = readString(inputStream)
              val action = PopulationAction(inputStream.readByte)
              val checksum = inputStream.readByte
              checkChecksum(bArr, length) match {
                case true =>
                  val (remaining, messages, status) = extract(inputStream.readAllBytes)
                  (remaining, CreatePolicy(species = species, action = action) +: messages, status)
                case false =>
                  (bArr, Seq.empty[Message], InvalidChecksum)
              }

            case Code.CreatePolicy =>
              (bArr, Seq.empty[Message], Success)

            case Code.DeletePolicy if length <= bArr.length =>
              val policy = inputStream.readInt
              val checksum = inputStream.readByte
              checkChecksum(bArr, length) match {
                case true =>
                  val (remaining, messages, status) = extract(inputStream.readAllBytes)
                  (remaining, DeletePolicy(length = length, policy = policy) +: messages, status)
                case false =>
                  (bArr, Seq.empty[Message], InvalidChecksum)
              }
            case Code.DeletePolicy =>
              (bArr, Seq.empty[Message], Success)

            case Code.PolicyResult if length <= bArr.length =>
              val policy = inputStream.readInt
              val checksum = inputStream.readByte
              checkChecksum(bArr, length) match {
                case true =>
                  val (remaining, messages, status) = extract(inputStream.readAllBytes)
                  (remaining, PolicyResult(length = length, policy = policy) +: messages, status)
                case false =>
                  (bArr, Seq.empty[Message], InvalidChecksum)
              }

            case Code.PolicyResult =>
              (bArr, Seq.empty[Message], Success)

            case Code.SiteVisit if length <= bArr.length =>
              val site = inputStream.readInt
              val populationsLength = inputStream.readInt
              val populations = (0 until populationsLength).map { _ =>
                val species = readString(inputStream)
                val count = inputStream.readInt
                PopulationCount(species = species, count = count)
              }.toArray
              val checksum = inputStream.readByte
              checkChecksum(bArr, length) match {
                case true =>
                  val (remaining, messages, status) = extract(inputStream.readAllBytes)
                  (
                    remaining,
                    SiteVisit(
                      site = site,
                      populations = populations
                    ) +: messages,
                    status
                  )
                case false =>
                  (bArr, Seq.empty[Message], InvalidChecksum)
              }

              case Code.SiteVisit =>
                (bArr, Seq.empty[Message], Success)

              case _ =>
                (bArr, Seq.empty[Message], UnknownMessageType)
          }
      }

    extract(prev ++ bArr)
  }

  def serialize(msg: Message) = {
    def writeString(msg: String, oos: DataOutputStream) = {
      oos.writeInt(msg.length)
      msg.foreach(ch => oos.writeByte((ch & 0xFF).toByte))
    }

    val stream: ByteArrayOutputStream = new ByteArrayOutputStream()
    val oos = new DataOutputStream(stream)
    oos.writeByte(msg.code)
    oos.writeInt(msg.length)
    msg match {
      case h: Hello =>
        writeString(h.protocol, oos)
        oos.writeInt(h.version)
      case e: Error =>
        writeString(e.message, oos)
      case _: OK =>
        // Nothing to write
      case d: DialAuthority =>
        oos.writeInt(d.site)
      case t: TargetPopulations =>
        oos.writeInt(t.site)
        oos.writeInt(t.populations.length)
        t.populations.foreach { p =>
          writeString(p.species, oos)
          oos.writeInt(p.min)
          oos.writeInt(p.max)
        }
      case c: CreatePolicy =>
        writeString(c.species, oos)
        oos.writeByte(c.action.code)
      case d: DeletePolicy =>
        oos.writeInt(d.policy)
      case p: PolicyResult =>
        oos.writeInt(p.policy)
      case s: SiteVisit =>
        oos.writeInt(s.site)
        oos.writeInt(s.populations.length)
        s.populations.foreach { p =>
          writeString(p.species, oos)
          oos.writeInt(p.count)
        }
    }
    oos.writeByte(msg.checksum)
    oos.close
    stream.toByteArray
  }
}