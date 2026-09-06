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
