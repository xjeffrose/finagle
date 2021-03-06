package com.twitter.finagle.tracing

import com.twitter.finagle.{Context, ContextHandler}
import com.twitter.io.Buf

private[finagle] object TraceContext {
  val Key = Buf.Utf8("com.twitter.finagle.tracing.TraceContext")
  val KeyBytes = Context.keyBytes(Key)
}

/**
 * A context handler for Trace IDs.
 *
 * The wire format is (big-endian):
 *     ''spanId:8 parentId:8 traceId:8 flags:8''
 */
private[finagle] class TraceContext extends ContextHandler {
  val key = TraceContext.Key

  private[this] val local = new ThreadLocal[Array[Byte]] {
    override def initialValue() = new Array[Byte](32)
  }

  private def put64(bytes: Array[Byte], i: Int, l: Long) {
    bytes(i) = (l>>56 & 0xff).toByte
    bytes(i+1) = (l>>48 & 0xff).toByte
    bytes(i+2) = (l>>40 & 0xff).toByte
    bytes(i+3) = (l>>32 & 0xff).toByte
    bytes(i+4) = (l>>24 & 0xff).toByte
    bytes(i+5) = (l>>16 & 0xff).toByte
    bytes(i+6) = (l>>8 & 0xff).toByte
    bytes(i+7) = (l & 0xff).toByte
  }

  private def get64(bytes: Array[Byte], i: Int): Long = {
    ((bytes(i) & 0xff).toLong << 56) |
    ((bytes(i+1) & 0xff).toLong << 48) |
    ((bytes(i+2) & 0xff).toLong << 40) |
    ((bytes(i+3) & 0xff).toLong << 32) |
    ((bytes(i+4) & 0xff).toLong << 24) |
    ((bytes(i+5) & 0xff).toLong << 16) |
    ((bytes(i+6) & 0xff).toLong << 8) |
    (bytes(i+7) & 0xff).toLong
  }

  def handle(body: Buf) {
    if (body.length != 32)
      throw new IllegalArgumentException("Expected 32 bytes")

    val bytes = local.get()
    body.write(bytes, 0)

    val span64 = get64(bytes, 0)
    val parent64 = get64(bytes, 8)
    val trace64 = get64(bytes, 16)
    val flags64 = get64(bytes, 24)

    val flags = Flags(flags64)
    val sampled = if (flags.isFlagSet(Flags.SamplingKnown)) {
      Some(flags.isFlagSet(Flags.Sampled))
    } else None

    val traceId = TraceId(
      if (trace64 == parent64) None else Some(SpanId(trace64)),
      if (parent64 == span64) None else Some(SpanId(parent64)),
      SpanId(span64),
      sampled,
      flags)

    Trace.setId(traceId)
  }

  def emit(): Option[Buf] = {
    val flags = Trace.id._sampled match {
      case None =>
        Trace.id.flags
      case Some(true) =>
        Trace.id.flags.setFlag(Flags.SamplingKnown | Flags.Sampled)
      case Some(false) =>
        Trace.id.flags.setFlag(Flags.SamplingKnown)
    }

    val bytes = new Array[Byte](32)
    put64(bytes, 0, Trace.id.spanId.toLong)
    put64(bytes, 8, Trace.id.parentId.toLong)
    put64(bytes, 16, Trace.id.traceId.toLong)
    put64(bytes, 24, flags.toLong)
    Some(Buf.ByteArray(bytes))
  }
}
