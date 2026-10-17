package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.*
import java.io.IOException
import okhttp3.*
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class WebDavPlaybackSourceTest {
  private var closed = false
  private lateinit var sent: Request
  private fun source(status: Int, headers: Map<String, String> = emptyMap(), unknownLength: Boolean = false,
    owner: ProxyRequestScope = ProxyRequestScope(), size: Long = -1): WebDavRangeSource {
    val client = OkHttpClient.Builder().addInterceptor { chain ->
      sent = chain.request()
      val bodySource = object : ForwardingSource(Buffer().writeUtf8("abcdefgh")) {
        override fun close() { closed = true; super.close() }
      }.buffer()
      val body = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength() = if (unknownLength) -1L else 8L
        override fun source() = bodySource
      }
      Response.Builder().request(sent).protocol(Protocol.HTTP_1_1).code(status).message("test")
        .apply { headers.forEach { (key, value) -> header(key, value) } }.body(body).build()
    }.build()
    return WebDavRangeSource(client, Request.Builder().url("http://localhost/test").build(), size,
      "file", "\"v1\"", RangeSegmentCache(), owner)
  }

  @Test fun ignoredSmallAndNonzeroRangesRemainFullResponses() {
    for (range in listOf("bytes=0-3", "bytes=4-")) {
      val result = source(200).openPlayback(range)
      assertEquals(200, result.status)
      assertNull(result.contentRange)
      assertNull(result.acceptRanges)
      assertEquals(8L, result.length)
      assertEquals("abcdefgh", result.stream.use { it.readBytes().decodeToString() })
      assertTrue(closed)
    }
  }

  @Test fun shortenedPartialResponseUsesUpstreamLengthWithUnknownDirectorySize() {
    val result = source(206, mapOf("Content-Range" to "bytes 4-11/100", "Accept-Ranges" to "bytes"))
      .openPlayback("bytes=4-99")
    assertEquals(206, result.status)
    assertEquals("bytes 4-11/100", result.contentRange)
    assertEquals(8L, result.length)
    assertEquals("bytes", result.acceptRanges)
    assertEquals("bytes=4-99", sent.header("Range"))
    assertEquals("identity", sent.header("Accept-Encoding"))
    result.stream.close()
  }

  @Test fun unknownLengthFullResponseStaysUnknownAndHeadUsesHeaderLength() {
    val full = source(200, unknownLength = true).openPlayback(null)
    assertEquals(-1L, full.length)
    assertEquals("abcdefgh", full.stream.use { it.readBytes().decodeToString() })
    val head = source(200, mapOf("Content-Length" to "100")).openPlayback(null, head = true)
    assertEquals("HEAD", sent.method)
    assertNull(sent.header("Range"))
    assertEquals(100L, head.length)
    head.stream.close()
  }

  @Test fun badRangeLengthEncodingOrVersionIsRejectedAndClosed() {
    for (headers in listOf(
      mapOf("Content-Range" to "bytes 5-12/100"),
      mapOf("Content-Range" to "bytes 4-12/100"),
      mapOf("Content-Range" to "bytes 4-11/100", "Content-Encoding" to "gzip"),
      mapOf("Content-Range" to "bytes 4-11/100", "ETag" to "\"v2\""),
    )) {
      closed = false
      try { source(206, headers).openPlayback("bytes=4-99"); fail("Invalid response") } catch (_: IOException) { }
      assertTrue(closed)
    }
  }

  @Test fun ignoredRangeStillChecksVersionAndAuthenticationDoesNotRetry() {
    try { source(200, mapOf("ETag" to "\"v2\"")).openPlayback("bytes=0-"); fail("Changed version") }
    catch (_: IOException) { assertTrue(closed) }
    try { source(401).openPlayback("bytes=0-"); fail("Authentication failure") }
    catch (error: WebDavHttpStatusException) { assertEquals(401, error.status); assertTrue(closed) }
  }

  @Test fun endOfFileRangeResponseIsPreserved() {
    val result = source(416, mapOf("Content-Range" to "bytes */8")).openPlayback("bytes=8-")
    assertEquals(416, result.status)
    assertEquals("bytes */8", result.contentRange)
    result.stream.close()
  }

  @Test fun retiringPlaybackClosesBodyAndRejectsFurtherReads() {
    val owner = ProxyRequestScope()
    val result = source(200, owner = owner).openPlayback("bytes=0-")
    owner.close()
    assertTrue(closed)
    try { result.stream.read(); fail("Retired stream readable") } catch (_: IOException) { }
  }
}
