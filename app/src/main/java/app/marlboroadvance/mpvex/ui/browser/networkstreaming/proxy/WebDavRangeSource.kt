package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.RangeSegmentCache
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/** HTTP range validation shared by direct reads and cached/gap composition. */
internal class WebDavRangeSource(
  private val client: OkHttpClient,
  private val request: Request,
  private val size: Long,
  private val identity: String,
  etag: String?,
  private val cache: RangeSegmentCache,
) {
  private val strongEtag = etag?.trim()?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }

  fun open(start: Long, end: Long): InputStream {
    require(start >= 0 && end >= start && end < size)
    // Without a strong validator, do not assemble bytes from multiple requests.
    return if (strongEtag != null) {
      CachedRangeInputStream(start, end, { from, until -> cache.next(identity, from, until) }, ::openRemote)
    } else {
      openRemote(start, end)
    }
  }

  private fun openRemote(start: Long, end: Long): InputStream {
    val count = end - start + 1
    if (count <= RangeSegmentCache.MAX_SEGMENT_BYTES) {
      cache.get(identity, start, end)?.let { return ByteArrayInputStream(it) }
    }
    val builder = request.newBuilder().header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity")
    strongEtag?.let { builder.header("If-Match", it) }
    val response = client.newCall(builder.build()).execute()
    try {
      if (response.code != 206) throw IOException("Range rejected (HTTP ${response.code})")
      if (response.header("Content-Range") != "bytes $start-$end/$size") throw IOException("Mismatched range")
      val returnedEtag = response.header("ETag")
      if (strongEtag != null && returnedEtag != null && returnedEtag != strongEtag) throw IOException("File version changed")
      val body = response.body ?: throw IOException("Missing range body")
      if (body.contentLength() >= 0 && body.contentLength() != count) throw IOException("Mismatched range length")
      val stream = body.byteStream()
      val ownedStream = object : FilterInputStream(stream) {
        override fun close() = response.close()
      }
      if (count <= RangeSegmentCache.MAX_SEGMENT_BYTES) {
        return CachingRangeInputStream(ownedStream, count.toInt()) { bytes ->
          cache.put(identity, start, end, bytes)
        }
      }
      return ownedStream
    } catch (e: Exception) {
      response.close()
      throw e
    }
  }
}
