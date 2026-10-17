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
  private val owner: ProxyRequestScope = ProxyRequestScope(),
  private val shared: SharedRangeReads? = null,
) {
  private val strongEtag = etag?.trim()?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }

  fun open(start: Long, end: Long): InputStream {
    require(start >= 0 && end >= start && end < size)
    // Without a strong validator, do not assemble bytes from multiple requests.
    owner.checkActive()
    val stream = if (strongEtag != null) {
      CachedRangeInputStream(start, end, { from, until -> cache.next(identity, from, until) }, ::openRemote)
    } else {
      openRemote(start, end)
    }
    return owner.own(stream)
  }

  fun openFull(): InputStream {
    owner.checkActive()
    if (size > 0 && strongEtag != null && cache.next(identity, 0, size - 1) != null) return open(0, size - 1)
    return owner.own(execute(request, owner) { response ->
      if (response.code != 200) throw IOException("Full request rejected (HTTP ${response.code})")
    })
  }

  data class PlaybackResponse(val status: Int, val length: Long, val contentRange: String?,
    val acceptRanges: String?, val stream: InputStream)

  /** Playback preserves upstream HTTP semantics; probe/cache reads remain strictly bounded. */
  fun openPlayback(range: String?, head: Boolean = false): PlaybackResponse {
    val requested = range?.let { Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(it) }
    if (range != null && (requested == null || requested.groupValues.drop(1).all { it.isEmpty() } ||
      requested.groupValues.drop(1).any { it.isNotEmpty() && it.toLongOrNull() == null })) {
      throw IOException("Invalid playback range")
    }
    val builder = request.newBuilder().header("Accept-Encoding", "identity")
    if (head) builder.head() else builder.get()
    if (range != null && !head) builder.header("Range", range) else builder.removeHeader("Range")
    strongEtag?.let { builder.header("If-Match", it) }
    var status = 0
    var length = -1L
    var contentRange: String? = null
    var acceptRanges: String? = null
    val stream = execute(builder.build(), owner) { response ->
      status = response.code
      if (status != 200 && status != 206 && status != 416) throw WebDavHttpStatusException(status)
      if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) {
        throw IOException("Encoded playback response rejected")
      }
      val returnedEtag = response.header("ETag")
      if (strongEtag != null && returnedEtag != null && returnedEtag != strongEtag) throw IOException("File version changed")
      length = if (head) response.header("Content-Length")?.toLongOrNull() ?: -1L
        else response.body?.contentLength() ?: -1L
      acceptRanges = response.header("Accept-Ranges")?.takeIf { it == "bytes" || it == "none" }
      if (status == 416) {
        contentRange = response.header("Content-Range")?.takeIf { Regex("bytes \\*/[0-9]+").matches(it) }
        return@execute
      }
      if (status == 206) {
        val header = response.header("Content-Range") ?: throw IOException("Missing playback range")
        val actual = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)").matchEntire(header)
          ?: throw IOException("Invalid playback range response")
        val start = actual.groupValues[1].toLongOrNull() ?: throw IOException("Invalid range start")
        val end = actual.groupValues[2].toLongOrNull() ?: throw IOException("Invalid range end")
        val total = actual.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (requested == null || head || end < start || end == Long.MAX_VALUE ||
          (actual.groupValues[3] != "*" && (total == null || total <= end))) throw IOException("Invalid range bounds")
        val expectedStart = if (requested.groupValues[1].isNotEmpty()) requested.groupValues[1].toLongOrNull()
          else requested.groupValues[2].toLongOrNull()?.takeIf { it > 0 }?.let { suffix -> total?.let { (it - suffix).coerceAtLeast(0) } }
        val expectedEnd = if (requested.groupValues[1].isNotEmpty()) requested.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() else null
        if (start != expectedStart || (expectedEnd != null && end > expectedEnd)) throw IOException("Mismatched playback range")
        val count = end - start + 1
        if (length >= 0 && length != count) throw IOException("Mismatched playback length")
        length = count
        contentRange = header
      }
      // A 200 response to Range means the whole representation, even for nonzero offsets.
      // Forward it as 200 so the demuxer can decide whether sequential playback is possible.
    }
    return PlaybackResponse(status, length, contentRange, acceptRanges, owner.own(stream))
  }

  private fun openRemote(start: Long, end: Long): InputStream {
    val count = end - start + 1
    if (count <= RangeSegmentCache.MAX_SEGMENT_BYTES) {
      cache.get(identity, start, end)?.let { return ByteArrayInputStream(it) }
      if (strongEtag != null) {
        shared?.open(
          SharedRangeReads.Key(identity, request.url.toString(), request.header("Authorization"), start, end),
          owner,
          source = { downloadOwner -> openHttp(start, end, downloadOwner) },
          completed = { cache.put(identity, start, end, it) },
        )?.let { return it }
      }
    }
    val stream = openHttp(start, end, owner)
    return if (count <= RangeSegmentCache.MAX_SEGMENT_BYTES) {
      CachingRangeInputStream(stream, count.toInt()) { cache.put(identity, start, end, it) }
    } else stream
  }

  private fun openHttp(start: Long, end: Long, requestOwner: ProxyRequestScope): InputStream {
    val count = end - start + 1
    val builder = request.newBuilder().header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity")
    strongEtag?.let { builder.header("If-Match", it) }
    return execute(builder.build(), requestOwner) { response ->
      if (response.code != 206) throw IOException("Range rejected (HTTP ${response.code})")
      if (response.header("Content-Range") != "bytes $start-$end/$size") throw IOException("Mismatched range")
      val returnedEtag = response.header("ETag")
      if (strongEtag != null && returnedEtag != null && returnedEtag != strongEtag) throw IOException("File version changed")
      val body = response.body ?: throw IOException("Missing range body")
      if (body.contentLength() >= 0 && body.contentLength() != count) throw IOException("Mismatched range length")
      if (response.header("Content-Encoding")?.let { it != "identity" } == true) throw IOException("Encoded range rejected")
    }
  }

  private fun execute(request: Request, requestOwner: ProxyRequestScope, validate: (okhttp3.Response) -> Unit): InputStream {
    requestOwner.checkActive()
    val call = client.newCall(request)
    val unbind = requestOwner.onCancel { call.cancel() }
    val response = try { call.execute() } catch (e: Exception) { unbind(); throw e }
    try {
      requestOwner.checkActive()
      validate(response)
      val stream = response.body?.byteStream() ?: throw IOException("Missing response body")
      return object : FilterInputStream(stream) {
        override fun close() {
          try { response.close() } finally { unbind() }
        }
      }
    } catch (e: Exception) {
      response.close()
      unbind()
      throw e
    }
  }
}

internal class WebDavHttpStatusException(val status: Int) : IOException("WebDAV HTTP $status")
