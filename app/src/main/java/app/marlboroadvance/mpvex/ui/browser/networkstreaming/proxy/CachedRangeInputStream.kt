package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.RangeSegmentCache
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Concatenates cached parts and single remote gaps without buffering a whole request. */
internal class CachedRangeInputStream(
  start: Long,
  private val end: Long,
  private val cached: (Long, Long) -> RangeSegmentCache.Slice?,
  private val remote: (Long, Long) -> InputStream,
) : InputStream() {
  private var position = start
  private var current: InputStream? = null
  private var remaining = 0L
  private var closed = false

  init {
    require(start >= 0 && end >= start)
    openNext()
  }

  private fun openNext() {
    val part = cached(position, end)
    if (part != null && part.start == position) {
      current = ByteArrayInputStream(part.bytes)
      remaining = part.bytes.size.toLong()
    } else {
      val gapEnd = part?.let { it.start - 1 } ?: end
      current = remote(position, gapEnd)
      remaining = gapEnd - position + 1
    }
  }

  override fun read(): Int {
    val one = ByteArray(1)
    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
  }

  override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
    if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
    if (closed) throw IOException("Range closed")
    if (length == 0) return 0
    if (position > end) return -1
    if (current == null) openNext()
    val count = current!!.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
    if (count < 0) throw EOFException("Incomplete range")
    position += count
    remaining -= count
    if (remaining == 0L) {
      current!!.close()
      current = null
    }
    return count
  }

  override fun available(): Int = if (closed) 0 else minOf(current?.available()?.toLong() ?: 0L, remaining).toInt()

  override fun close() {
    if (closed) return
    closed = true
    current?.close()
    current = null
  }
}
