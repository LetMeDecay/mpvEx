package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.WebDavRangeSource
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WebDavRangeSourceTest {
  private val data = ByteArray(RangeSegmentCache.MAX_SEGMENT_BYTES + 64) { (it % 251).toByte() }
  private val requests = mutableListOf<Request>()
  private val cache = RangeSegmentCache()

  private fun source(etag: String? = "\"v1\"", status: Int = 206, returnedEtag: String = "\"v1\"", short: Boolean = false): WebDavRangeSource {
    val client = OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      requests.add(request)
      val parts = request.header("Range")!!.removePrefix("bytes=").split('-')
      val start = parts[0].toInt()
      val end = parts[1].toInt()
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
        .header("Content-Range", "bytes $start-$end/${data.size}")
        .header("ETag", returnedEtag)
        .body(data.copyOfRange(start, end + if (short) 0 else 1).toResponseBody())
        .build()
    }.build()
    return WebDavRangeSource(client, Request.Builder().url("http://localhost/test").build(), data.size.toLong(), "file-v1", etag, cache)
  }

  @Test fun largeRequestCombinesHeadMiddleTailAndDownloadsOnlyGaps() {
    for (start in listOf(0, 32, data.size - 4)) {
      cache.put("file-v1", start.toLong(), start + 3L, data.copyOfRange(start, start + 4))
    }
    assertArrayEquals(data, source().open(0, data.lastIndex.toLong()).use { it.readBytes() })
    assertEquals(listOf("bytes=4-31", "bytes=36-${data.size - 5}"), requests.map { it.header("Range") })
    assertTrue(requests.all { it.header("If-Match") == "\"v1\"" })
    assertTrue(cache.totalBytes() <= RangeSegmentCache.MAX_TOTAL_BYTES)
  }

  @Test fun weakValidatorKeepsLargeRequestAsOneRemoteRead() {
    cache.put("file-v1", 0, 3, byteArrayOf(99, 99, 99, 99))
    assertArrayEquals(data, source("W/\"v1\"").open(0, data.lastIndex.toLong()).use { it.readBytes() })
    assertEquals(1, requests.size)
    assertNull(requests.single().header("If-Match"))
  }

  @Test fun closingOnCachedPrefixDoesNotOpenUnusedRemoteGap() {
    cache.put("file-v1", 0, 3, data.copyOfRange(0, 4))
    val stream = source().open(0, data.lastIndex.toLong())
    assertEquals(data[0].toInt(), stream.read())
    stream.close()
    assertTrue(requests.isEmpty())
    try { stream.read(); fail("Closed stream reopened") } catch (_: IOException) { }
  }

  @Test fun changedVersionCannotBeAppendedToCachedBytes() {
    cache.put("file-v1", 0, 3, data.copyOfRange(0, 4))
    try {
      source(returnedEtag = "\"v2\"").open(0, data.lastIndex.toLong()).use { it.readBytes() }
      fail("Mixed versions")
    } catch (_: IOException) { }
    assertEquals(1, cache.entryCount())
  }

  @Test fun ignoredRangeAndShortResponseNeverEnterCache() {
    for (reader in listOf(source(status = 200), source(short = true))) {
      try { reader.open(0, 15).use { it.readBytes() }; fail("Invalid response accepted") } catch (_: IOException) { }
    }
    assertEquals(0, cache.entryCount())
  }

  @Test fun ignoredRangeRemainsRejectedForLargeProbeReads() {
    try {
      source(status = 200).open(0, data.lastIndex.toLong()).use { it.readBytes() }
      fail("Probe accepted full response")
    } catch (_: IOException) { }
    assertEquals(0, cache.entryCount())
  }

  @Test fun ignoredRangeAtNonZeroOffsetIsStillRejected() {
    try {
      source(status = 200).open(32, data.lastIndex.toLong()).use { it.readBytes() }
      fail("Ignored range accepted at non-zero offset")
    } catch (_: IOException) { }
  }

  @Test fun cachedInteriorRangeStartsAtRequestedOffset() {
    cache.put("file-v1", 0, 31, data.copyOfRange(0, 32))
    assertArrayEquals(data.copyOfRange(7, 48), source().open(7, 47).use { it.readBytes() })
    assertEquals("bytes=32-47", requests.single().header("Range"))
  }

  private fun playbackSource(statuses: List<Int>, body: ByteArray = data): Pair<WebDavRangeSource, MutableList<Request>> {
    val seen = mutableListOf<Request>()
    val client = OkHttpClient.Builder().addInterceptor { chain ->
      val request = chain.request()
      seen.add(request)
      val code = statuses[(seen.size - 1).coerceAtMost(statuses.lastIndex)]
      Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .header("Content-Range", "bytes 0-9/${body.size}")
        .body(if (code == 200 || code == 206) body.copyOfRange(0, 10).toResponseBody() else "".toResponseBody())
        .build()
    }.build()
    val source = WebDavRangeSource(
      client, Request.Builder().url("http://localhost/test").build(), body.size.toLong(), "file-v1", null, cache,
    )
    return source to seen
  }

  @Test fun playbackRetriesOneTransientServerError() {
    val (source, seen) = playbackSource(listOf(503, 206))
    val response = source.openPlayback("bytes=0-9")
    try {
      assertEquals(206, response.status)
      assertEquals(2, seen.size)
    } finally {
      response.stream.close()
    }
  }

  @Test fun playbackRetriesAtMostOnce() {
    val (source, seen) = playbackSource(listOf(503, 503, 206))
    try {
      source.openPlayback("bytes=0-9").stream.close()
      fail("Persistent failure accepted")
    } catch (_: IOException) { }
    assertEquals(2, seen.size)
  }

  @Test fun playbackDoesNotRetryClientErrors() {
    val (source, seen) = playbackSource(listOf(404, 206))
    try {
      source.openPlayback("bytes=0-9").stream.close()
      fail("404 accepted")
    } catch (_: IOException) { }
    assertEquals(1, seen.size)
  }
}
