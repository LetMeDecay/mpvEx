package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.CachingRangeInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class CachingRangeInputStreamTest {
  @Test fun noReadAheadAndOnlyCompleteConsumptionPublishes() {
    var reads = 0
    var publications = 0
    var cached: ByteArray? = null
    val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
      override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return super.read(b, off, minOf(1, len)) }
    }
    val stream = CachingRangeInputStream(input, 4) { cached = it; publications++ }
    assertEquals(0, reads)
    assertEquals(1, stream.read())
    assertEquals(1, reads)
    assertNull(cached)
    assertArrayEquals(byteArrayOf(2, 3, 4), stream.readBytes())
    assertArrayEquals(byteArrayOf(1, 2, 3, 4), cached)
    assertEquals(-1, stream.read())
    stream.close()
    assertEquals(1, publications)
  }

  @Test fun earlyCloseAndShortOrFailedReadsNeverPublish() {
    var publications = 0
    val early = CachingRangeInputStream(ByteArrayInputStream(byteArrayOf(1, 2)), 2) { publications++ }
    assertEquals(1, early.read())
    early.close()
    try { early.read(); fail("Closed stream read") } catch (_: IOException) { }
    val short = CachingRangeInputStream(ByteArrayInputStream(byteArrayOf(1)), 2) { publications++ }
    try { short.use { it.readBytes() }; fail("Short response accepted") } catch (_: EOFException) { }
    var closed = false
    val failing = object : java.io.InputStream() {
      override fun read(): Int = throw IOException("test failure")
      override fun close() { closed = true }
    }
    try { CachingRangeInputStream(failing, 2) { publications++ }.read(); fail("Failure hidden") } catch (_: IOException) { }
    assertTrue(closed)
    assertEquals(0, publications)
  }

  @Test fun oversizedBodyCannotExpandTheCacheEntry() {
    var cached: ByteArray? = null
    CachingRangeInputStream(ByteArrayInputStream(ByteArray(20) { it.toByte() }), 4) { cached = it }.use {
      assertArrayEquals(byteArrayOf(0, 1, 2, 3), it.readBytes())
    }
    assertEquals(4, cached!!.size)
  }
}
