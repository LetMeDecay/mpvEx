package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/**
 * Reads duration and video dimensions directly from MP4/MKV container headers.
 *
 * This is a bounded alternative to [android.media.MediaMetadataRetriever] for the
 * common formats: callers fetch a small head (and, for MP4 whose `moov` sits at the
 * end, a small tail) and parse it here. Anything unrecognised returns null so the
 * caller can fall back to the retriever through the local proxy.
 */
internal object ContainerHeaderProbe {
  data class Header(val durationMs: Long, val width: Int, val height: Int)

  private const val MAX_DEPTH = 8

  fun parse(bytes: ByteArray): Header? =
    when {
      isMatroska(bytes) -> parseMatroska(bytes)
      else -> parseIsoBmff(bytes)
    }?.takeIf { it.durationMs > 0L }

  // ---------------------------------------------------------------- Matroska

  private fun isMatroska(bytes: ByteArray): Boolean =
    bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
      bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()

  private fun parseMatroska(bytes: ByteArray): Header? {
    var timecodeScale = 1_000_000L
    var duration = 0.0
    var width = 0
    var height = 0
    var foundDuration = false

    fun walk(start: Int, end: Int, depth: Int) {
      if (depth > MAX_DEPTH) return
      var pos = start
      while (pos < end) {
        val id = readEbmlId(bytes, pos, end) ?: return
        pos = id.second
        val size = readEbmlSize(bytes, pos, end) ?: return
        pos = size.second
        val dataStart = pos
        val dataEnd = if (size.first < 0) end else (pos + size.first).coerceAtMost(end.toLong()).toInt()
        if (dataStart > dataEnd) return
        when (id.first) {
          0x18538067L, 0x1549A966L, 0x1654AE6BL, 0xAEL, 0xE0L -> walk(dataStart, dataEnd, depth + 1)
          0x2AD7B1L -> readUnsigned(bytes, dataStart, dataEnd)?.let { timecodeScale = it }
          0x4489L -> readFloat(bytes, dataStart, dataEnd)?.let { duration = it; foundDuration = true }
          0xB0L -> readUnsigned(bytes, dataStart, dataEnd)?.let { width = it.toInt() }
          0xBAL -> readUnsigned(bytes, dataStart, dataEnd)?.let { height = it.toInt() }
        }
        // Unknown-size elements (streamed Segment) keep walking instead of skipping.
        pos = if (size.first < 0) dataStart else dataEnd
      }
    }

    walk(0, bytes.size, 0)
    if (!foundDuration || duration <= 0.0) return null
    val durationMs = (duration * timecodeScale / 1_000_000.0).toLong()
    return Header(durationMs, width, height)
  }

  private fun readEbmlId(bytes: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
    if (start >= end) return null
    val first = bytes[start].toInt() and 0xFF
    val length = when {
      first and 0x80 != 0 -> 1
      first and 0x40 != 0 -> 2
      first and 0x20 != 0 -> 3
      first and 0x10 != 0 -> 4
      else -> return null
    }
    if (start + length > end) return null
    var value = 0L
    for (index in 0 until length) value = (value shl 8) or (bytes[start + index].toLong() and 0xFF)
    return value to (start + length)
  }

  /** Returns size and next position; size -1 means "unknown" (all value bits set). */
  private fun readEbmlSize(bytes: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
    if (start >= end) return null
    val first = bytes[start].toInt() and 0xFF
    var mask = 0x80
    var length = 1
    while (length <= 8 && first and mask == 0) {
      mask = mask shr 1
      length++
    }
    if (length > 8 || start + length > end) return null
    var value = (first and (mask - 1)).toLong()
    var unknown = (first and (mask - 1)) == mask - 1
    for (index in 1 until length) {
      val b = bytes[start + index].toLong() and 0xFF
      value = (value shl 8) or b
      if (b != 0xFFL) unknown = false
    }
    return (if (unknown) -1L else value) to (start + length)
  }

  private fun readUnsigned(bytes: ByteArray, start: Int, end: Int): Long? {
    if (start >= end || end - start > 8) return null
    var value = 0L
    for (index in start until end) value = (value shl 8) or (bytes[index].toLong() and 0xFF)
    return value
  }

  private fun readFloat(bytes: ByteArray, start: Int, end: Int): Double? = when (end - start) {
    4 -> java.lang.Float.intBitsToFloat(
      ((bytes[start].toInt() and 0xFF) shl 24) or ((bytes[start + 1].toInt() and 0xFF) shl 16) or
        ((bytes[start + 2].toInt() and 0xFF) shl 8) or (bytes[start + 3].toInt() and 0xFF),
    ).toDouble()
    8 -> java.lang.Double.longBitsToDouble(readUnsigned(bytes, start, end) ?: return null)
    else -> null
  }

  // -------------------------------------------------------------------- MP4

  private fun parseIsoBmff(bytes: ByteArray): Header? {
    var mvhdTimescale = 0L
    var mvhdDuration = 0L
    var width = 0
    var height = 0

    fun readMvhd(data: Int, end: Int) {
      if (data + 4 > end) return
      val version = bytes[data].toInt() and 0xFF
      if (version == 1) {
        if (data + 32 > end) return
        mvhdTimescale = readU32(bytes, data + 20)
        mvhdDuration = readU64(bytes, data + 24)
      } else {
        if (data + 20 > end) return
        mvhdTimescale = readU32(bytes, data + 12)
        mvhdDuration = readU32(bytes, data + 16)
      }
    }

    fun readTkhd(data: Int, end: Int) {
      if (data + 4 > end) return
      val version = bytes[data].toInt() and 0xFF
      val base = if (version == 1) data + 88 else data + 76
      if (base + 8 > end) return
      val w = (readU32(bytes, base) ushr 16).toInt()
      val h = (readU32(bytes, base + 4) ushr 16).toInt()
      if (w > 0 && h > 0) {
        width = w
        height = h
      }
    }

    fun walk(start: Int, end: Int, depth: Int) {
      if (depth > MAX_DEPTH) return
      var pos = start
      while (pos + 8 <= end) {
        var size = readU32(bytes, pos)
        val type = String(bytes, pos + 4, 4)
        var header = 8
        if (size == 1L) {
          if (pos + 16 > end) return
          size = readU64(bytes, pos + 8)
          header = 16
        } else if (size == 0L) {
          size = (end - pos).toLong()
        }
        if (size < header || pos + size > end) return
        val data = pos + header
        val dataEnd = (pos + size).toInt()
        when (type) {
          "moov", "trak", "mdia" -> walk(data, dataEnd, depth + 1)
          "mvhd" -> readMvhd(data, dataEnd)
          "tkhd" -> readTkhd(data, dataEnd)
        }
        pos = dataEnd
      }
    }

    walk(0, bytes.size, 0)
    if (mvhdDuration <= 0 || mvhdTimescale <= 0) {
      // A tail slice may start inside `mdat`; locate the `moov` box by signature.
      val moov = indexOf(bytes, byteArrayOf('m'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte()))
      if (moov >= 4) walk(moov - 4, bytes.size, 0) else return null
    }
    if (mvhdDuration <= 0 || mvhdTimescale <= 0) return null
    val durationMs = mvhdDuration * 1000 / mvhdTimescale
    return Header(durationMs, width, height)
  }

  private fun readU32(bytes: ByteArray, at: Int): Long =
    ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
      ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

  private fun readU64(bytes: ByteArray, at: Int): Long =
    (readU32(bytes, at) shl 32) or readU32(bytes, at + 4)

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (index in 0..haystack.size - needle.size) {
      for (offset in needle.indices) if (haystack[index + offset] != needle[offset]) continue@outer
      return index
    }
    return -1
  }
}
