package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Return bytes immediately, publishing a bounded cache entry only on complete consumption. */
internal class CachingRangeInputStream(
  private val source: InputStream,
  expected: Int,
  private val completed: (ByteArray) -> Unit,
) : InputStream() {
  private var buffer: ByteArray? = ByteArray(expected)
  private var position = 0
  private val expectedSize = expected
  private var closed = false

  override fun read(): Int {
    val one = ByteArray(1)
    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
  }

  override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
    if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
    if (closed) throw IOException("Range closed")
    if (length == 0) return 0
    if (position == expectedSize) return -1
    try {
      val count = source.read(bytes, offset, minOf(length, expectedSize - position))
      if (count < 0) throw EOFException("Incomplete range")
      bytes.copyInto(buffer!!, position, offset, offset + count)
      position += count
      if (position == expectedSize) {
        val finished = buffer!!
        buffer = null
        completed(finished)
        source.close()
      }
      return count
    } catch (e: Exception) {
      runCatching { close() }
      throw e
    }
  }

  override fun available(): Int = if (closed) 0 else minOf(source.available(), expectedSize - position)

  override fun close() {
    if (closed) return
    closed = true
    buffer = null
    source.close()
  }
}
