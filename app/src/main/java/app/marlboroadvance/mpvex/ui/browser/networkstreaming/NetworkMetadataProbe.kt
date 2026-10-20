package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.webDavPath
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
import `is`.xyz.mpv.FastThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Probes metadata (duration, thumbnail) for network files by streaming them
 * through the local HTTP proxy.
 *
 * Traffic optimizations:
 *  - Thumbnails are generated with FastThumbnails (libmpv), which uses HTTP
 *    Range requests for precise seeking and only downloads the bytes it needs,
 *    instead of MediaMetadataRetriever which can download large chunks.
 *  - Durations are read with MediaMetadataRetriever (only the container header
 *    is fetched), and both results are cached persistently so they are never
 *    re-fetched unless the file actually changes.
 *
 * Cache invalidation is based on connection id + path + size + etag +
 * lastModified (stable across servers/protocols), so a cached entry survives
 * app restarts and is invalidated when the remote file actually changes.
 */
object NetworkMetadataProbe {
  private const val TAG = "NetworkMetadataProbe"

  // Thumbnail dimension (longest side). 160 keeps previews crisp while roughly
  // halving decode cost on the (serial) native grabThumbnailFast path.
  private const val THUMBNAIL_SIZE = 160

  // WebP/JPEG compression quality. Thumbnails don't need 90; ~80 keeps visual
  // quality while shrinking the disk cache noticeably.
  private const val THUMBNAIL_JPEG_QUALITY = 80

  // Decode attempts per timestamp: hardware first, software fallback.
  private val hardwareDecodeAttempts = if (THUMBNAIL_PREFER_HW) booleanArrayOf(true, false) else booleanArrayOf(false)

  // Try hardware decode first (lower CPU/power on most devices), falling back to
  // software. Flip to false if hardware decode proves unstable on a given device.
  private const val THUMBNAIL_PREFER_HW = true

  private val cacheGeneration = MetadataCacheGeneration()
  // Access only under cacheGeneration's lock, together with cache writes.
  private val versionIndex = MetadataVersionIndex()
  private var cacheInitialized = false
  internal val cacheChanges = cacheGeneration.changes

  fun cacheToken(connection: NetworkConnection, file: NetworkFile): Long =
    cacheGeneration.token(cacheKey(connection, file))

  private val durationCache = ConcurrentHashMap<String, Long>()
  // Eviction releases this cache's reference only; Compose may still draw the bitmap.
  private val thumbnailCache = ByteSizeLruCache<String, Bitmap>(8L * 1024 * 1024) {
    it.allocationByteCount.toLong()
  }
  private val resolutionCache = ConcurrentHashMap<String, Pair<Int, Int>>()

  // O(1) index from "<connectionId>::<filePath>" to the exact cache key, so
  // playback-list lookups (which only know connection id + path) never have to
  // scan the whole SharedPreferences file.
  private val keyIndex = ConcurrentHashMap<String, String>()

  // Thumbnail candidate timestamps (seconds). We try them in order and only
  // move to the next one when the previous attempt failed/returned an invalid
  // (black) frame, so normal videos cost exactly one seek.
  private val thumbnailTimestamps = doubleArrayOf(2.0, 5.0, 10.0, 30.0)

  enum class ThumbnailPriority { CURRENT, VISIBLE, PREFETCH, BACKGROUND }

  private data class ThumbnailInput(val generation: Long, val connection: NetworkConnection, val file: NetworkFile)

  private val thumbnailDispatcher = PriorityProbeDispatcher<String, ThumbnailInput, Bitmap>(
    CoroutineScope(SupervisorJob() + Dispatchers.IO),
    produce = { input, publish ->
      val bitmap = probeThumbnailNow(input.connection, input.file, input.generation)
      if (bitmap != null) {
        thumbnailWrites.publishAndEnqueue(
          ThumbnailWrite(input.connection, input.file, input.generation, bitmap),
        ) { publish(bitmap) }
      } else publish(null)
    },
    onFailure = { Log.w(TAG, "Thumbnail request failed (${it.javaClass.simpleName})") },
  )
  // MediaMetadataRetriever instances are independent and truly parallel, so
  // durations keep a higher concurrency for real gains.
  private val durationSemaphore = Semaphore(6)
  // Bounded, auth-carrying client for direct container header reads.
  private val headerProbeClient = okhttp3.OkHttpClient.Builder()
    .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    .build()

  private val durationRequests = SharedProbeRequests<String, Long>(
    CoroutineScope(SupervisorJob() + Dispatchers.IO),
  )
  private val thumbnailReads = SharedProbeRequests<String, Bitmap?>(
    CoroutineScope(SupervisorJob() + Dispatchers.IO),
  )

  private data class ThumbnailWrite(
    val connection: NetworkConnection,
    val file: NetworkFile,
    val generation: Long,
    val bitmap: Bitmap,
  )

  private val thumbnailWrites = ThumbnailPersistenceQueue<ThumbnailWrite>(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    persist = { persistThumbnail(it) },
    onFailure = { Log.w(TAG, "Unable to persist thumbnail", it) },
  )

  // Preview cache maintenance runs off the probe path and never blocks a caller.
  private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val thumbnailWriteCount = java.util.concurrent.atomic.AtomicInteger()

  @Volatile
  private var appContext: Context? = null

  fun init(context: Context) {
    appContext = context.applicationContext
    if (cacheGeneration.read { cacheInitialized }) return
    // SharedPreferences can wait for its initial disk load; do that before taking the memory lock.
    val snapshot = prefs.all
    cacheGeneration.read {
      if (!cacheInitialized) {
        val obsoleteSuffixes = arrayOf(":itemDate", ":itemDateChecked", ":videoCreationDateCheckedV2")
        val editor = prefs.edit()
        snapshot.keys.forEach { key ->
          if (obsoleteSuffixes.any(key::endsWith)) {
            editor.remove(key)
          } else {
            versionIndex.add(key.removeSuffix(":w").removeSuffix(":h"))
          }
        }
        editor.apply()
        cacheInitialized = true
      }
    }
    // Keep the preview cache within its budget without delaying startup.
    maintenanceScope.launch { runCatching { thumbnailDisk.trim() } }
  }

  private val prefs by lazy {
    appContext!!.getSharedPreferences("network_metadata_cache", Context.MODE_PRIVATE)
  }

  private val thumbnailDisk by lazy {
    ThumbnailDiskStore(File(appContext!!.filesDir, "network_thumbnails"))
  }

  // Stable cache key: connection id + path + size + etag + lastModified.
  // size is used instead of lastModified alone because WebDAV/FTP lastModified
  // values can vary in precision across servers. Including etag (when the
  // protocol exposes one, e.g. WebDAV PROPFIND) catches the case where a file
  // was replaced in place with the same name and size.
  /**
   * Canonical identity shared by metadata caches, proxy range segments and
   * playlist retry tracking. Keep this in one place so every consumer treats
   * a replacement with the same path as a new remote object.
   */
  fun cacheIdentity(connection: NetworkConnection, file: NetworkFile): String =
    "${connection.id}::${file.path}::${file.size}::${file.etag ?: ""}::${file.lastModified}"

  private fun cacheKey(connection: NetworkConnection, file: NetworkFile): String =
    cacheIdentity(connection, file)

  /**
   * Returns the duration of a network file in milliseconds, or 0 if unknown.
   * Also extracts and caches the video resolution (width x height) in the same
   * pass, since MediaMetadataRetriever reads the container header anyway.
   * Checks memory cache, then persistent cache, then probes over the network.
   */
  suspend fun probeDuration(
    connection: NetworkConnection,
    file: NetworkFile,
  ): Long {
    if (file.isDirectory) return 0L
    val key = cacheKey(connection, file)

    val generation = cacheGeneration.token(key)
    cacheGeneration.read {
      durationCache[key] ?: prefs.getLong(key, 0L).takeIf { it > 0 }?.also { durationCache[key] = it }
    }?.let { return it }

    return durationRequests.run("$key#$generation") {
      probeDurationNow(connection, file, key, generation)
    }
  }

  private suspend fun probeDurationNow(
    connection: NetworkConnection,
    file: NetworkFile,
    key: String,
    generation: Long,
  ): Long {
    // Probe over the network (MediaMetadataRetriever only reads the container
    // header to extract the duration and resolution - minimal data transfer).
    val result =
      withContext(Dispatchers.IO) {
        durationSemaphore.withPermit {
          // A cached result or invalidation may arrive while this job waits.
          if (cacheGeneration.token(key) != generation) return@withPermit ProbeResult(0L, 0, 0)
          val cached = getCachedDuration(connection, file)
          if (cached > 0) return@withPermit ProbeResult(cached, 0, 0)
          NetworkPlaybackPolicy.shared.awaitMetadata()
          if (cacheGeneration.token(key) != generation) return@withPermit ProbeResult(0L, 0, 0)
          val afterWait = getCachedDuration(connection, file)
          if (afterWait > 0) return@withPermit ProbeResult(afterWait, 0, 0)
          // Common containers expose duration/geometry in their first (or last) bytes;
          // reading them directly avoids a second HTTP stack and a decoder start.
          probeContainerHeader(connection, file)?.let { return@withPermit it }
          val proxy = NetworkStreamingProxy.getInstance()
          val streamId = "probe_${connection.id}_${System.nanoTime()}"
          val url =
            proxy.registerStream(
              streamId = streamId,
              connection = connection,
              file = file,
            )
          try {
            val retriever = MediaMetadataRetriever()
            try {
              withProxyCancellation(proxy, streamId) {
                retriever.setDataSource(url)
                val duration =
                  retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
                val width =
                  retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?: 0
                val height =
                  retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?: 0
                ProbeResult(duration, width, height)
              }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              ProbeResult(0L, 0, 0)
            } finally {
              runCatching { retriever.release() }
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            ProbeResult(0L, 0, 0)
          } finally {
            proxy.unregisterStream(streamId)
          }
        }
      }

    return thumbnailDisk.transaction {
      val retired = mutableListOf<String>()
      val published = cacheGeneration.publish(key, generation) {
        if (result.duration > 0 || (result.width > 0 && result.height > 0)) {
          keyIndex["${connection.id}::${file.path}"] = key
          val editor = prefs.edit()
          // The startup index avoids scanning/copying all preferences for each video.
          (versionIndex.keysFor(connection.id, file.path) - key).forEach { stale ->
            editor.remove(stale).remove("${stale}:w").remove("${stale}:h")
            durationCache.remove(stale)
            thumbnailCache.remove(stale)
            resolutionCache.remove(stale)
            versionIndex.remove(stale)
            retired.add(stale)
          }
          versionIndex.add(key, connection.id, file.path)
          if (result.duration > 0) {
            durationCache[key] = result.duration
            editor.putLong(key, result.duration)
          }
          if (result.width > 0 && result.height > 0) {
            resolutionCache[key] = result.width to result.height
            editor.putLong("${key}:w", result.width.toLong())
            editor.putLong("${key}:h", result.height.toLong())
          }
          editor.apply()
        }
      }
      // Disk deletion is serialized with image writes, but never holds the memory lock.
      retired.forEach { stale -> runCatching { remove(connection.id, stale) } }
      if (published) result.duration else 0L
    }
  }

  /**
   * Direct bounded read of an MP4/Matroska header over WebDAV. Returns null when the
   * format is unknown, the server refuses ranges, or the header is not in the window,
   * in which case the caller keeps the proxy + MediaMetadataRetriever fallback.
   */
  private suspend fun probeContainerHeader(
    connection: NetworkConnection,
    file: NetworkFile,
  ): ProbeResult? = withContext(Dispatchers.IO) {
    if (connection.protocol != NetworkProtocol.WEBDAV) return@withContext null
    val protocol = if (connection.useHttps) "https" else "http"
    val path = webDavPath(connection.path, file.path)
    val url = "$protocol://${connection.host}:${connection.port}$path"
    val auth = if (connection.isAnonymous) null else okhttp3.Credentials.basic(connection.username, connection.password)
    val head = fetchHeaderBytes(url, auth, 0L, HEADER_HEAD_BYTES - 1L)
    ContainerHeaderProbe.parse(head ?: return@withContext null)?.let {
      return@withContext ProbeResult(it.durationMs, it.width, it.height)
    }
    if (file.size <= HEADER_HEAD_BYTES) return@withContext null
    val tailStart = (file.size - HEADER_TAIL_BYTES).coerceAtLeast(0L)
    val tail = fetchHeaderBytes(url, auth, tailStart, file.size - 1)
    ContainerHeaderProbe.parse(tail ?: return@withContext null)?.let {
      ProbeResult(it.durationMs, it.width, it.height)
    }
  }

  private fun fetchHeaderBytes(url: String, auth: String?, start: Long, end: Long): ByteArray? {
    val request = okhttp3.Request.Builder()
      .url(url)
      .header("Range", "bytes=$start-$end")
      .header("Accept-Encoding", "identity")
      .apply { if (auth != null) header("Authorization", auth) }
      .build()
    return try {
      headerProbeClient.newCall(request).execute().use { response ->
        if (response.code != 200 && response.code != 206) return null
        val body = response.body ?: return null
        val limit = (end - start + 1).toInt()
        val input = body.byteStream()
        val output = java.io.ByteArrayOutputStream(limit)
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
          val read = input.read(buffer, 0, minOf(buffer.size, remaining))
          if (read <= 0) break
          output.write(buffer, 0, read)
          remaining -= read
        }
        output.toByteArray().takeIf { it.isNotEmpty() }
      }
    } catch (e: Exception) {
      null
    }
  }

  private const val HEADER_HEAD_BYTES = 1024 * 1024
  private const val HEADER_TAIL_BYTES = 2 * 1024 * 1024

  private data class ProbeResult(
    val duration: Long,
    val width: Int,
    val height: Int,
  )

  /**
   * Returns a thumbnail frame for a network file, or null if it could not be generated.
   * Uses FastThumbnails (libmpv) which performs HTTP Range based seeking, so only
   * the necessary bytes are downloaded instead of the whole file.
   *
   * Decode strategy: hardware decode first, falling back to software decode on
   * failure (codec unsupported / seek failure / black frame). Timestamp strategy:
   * try 2s, 5s, 10s, 30s in order, only advancing when the previous attempt was
   * unusable - so a normal video costs a single request.
   */
  suspend fun probeThumbnail(
    connection: NetworkConnection,
    file: NetworkFile,
    priority: ThumbnailPriority = ThumbnailPriority.VISIBLE,
  ): Bitmap? {
    if (file.isDirectory) return null
    val key = cacheKey(connection, file)

    val generation = cacheGeneration.token(key)
    cachedThumbnail(key, connection.id)?.let { return it }
    return thumbnailDispatcher.submit("$key#$generation", ThumbnailInput(generation, connection, file), priority.ordinal)
  }

  private suspend fun cachedThumbnail(key: String, connectionId: Long): Bitmap? {
    val (generation, cached) = cacheGeneration.read {
      cacheGeneration.token(key) to thumbnailCache[key]
    }
    if (cached != null) return cached
    return thumbnailReads.run("$key#$generation") {
      if (cacheGeneration.token(key) != generation) return@run null
      cacheGeneration.read { thumbnailCache[key] }?.let { return@run it }
      val bitmap = try {
        thumbnailDisk.decode(connectionId, key, { cacheGeneration.token(key) == generation }) {
          BitmapFactory.decodeStream(it)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      } ?: return@run null
      currentCoroutineContext().ensureActive()
      val published = cacheGeneration.publish(key, generation) { thumbnailCache[key] = bitmap }
      if (!published) bitmap.recycle() // This new decode has never been exposed to a caller.
      bitmap.takeIf { published }
    }
  }

  private suspend fun probeThumbnailNow(
    connection: NetworkConnection,
    file: NetworkFile,
    generation: Long,
  ): Bitmap? {
    val key = cacheKey(connection, file)
    if (cacheGeneration.token(key) != generation) return null
    cachedThumbnail(key, connection.id)?.let { return it }
    NetworkPlaybackPolicy.shared.awaitMetadata()
    if (cacheGeneration.token(key) != generation) return null
    cachedThumbnail(key, connection.id)?.let { return it }
    val result =
      withContext(Dispatchers.IO) {
          val proxy = NetworkStreamingProxy.getInstance()
          val streamId = "thumb_${connection.id}_${System.nanoTime()}"
          val url =
            proxy.registerStream(
              streamId = streamId,
              connection = connection,
              file = file,
            )
          try {
            withProxyCancellation(proxy, streamId) { generateThumbnailWithFallbacks(url) }
          } finally {
            proxy.unregisterStream(streamId)
          }
      }

    val bitmap = result?.bitmap ?: return null
    currentCoroutineContext().ensureActive()
    val published = cacheGeneration.publish(key, generation) {
      thumbnailCache[key] = bitmap
      versionIndex.add(key, connection.id, file.path)
      keyIndex["${connection.id}::${file.path}"] = key
    }
    if (!published) bitmap.recycle()
    return bitmap.takeIf { published }
  }

  private suspend fun persistThumbnail(item: ThumbnailWrite) {
    val key = cacheKey(item.connection, item.file)
    val indexKey = "${item.connection.id}::${item.file.path}"
    fun canPublish(): Boolean = cacheGeneration.read {
      cacheGeneration.token(key) == item.generation && keyIndex[indexKey] == key
    }
    // Never republish memory here: clear/invalidation can happen during encoding.
    val written = thumbnailDisk.write(item.connection.id, key, ::canPublish) { temporary ->
      val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
      fun encode(format: Bitmap.CompressFormat): Boolean = FileOutputStream(temporary).use { out ->
        item.bitmap.compress(format, THUMBNAIL_JPEG_QUALITY, out)
      }
      runCatching { encode(format) }.getOrDefault(false) || encode(Bitmap.CompressFormat.JPEG)
    }
    if (written && thumbnailWriteCount.incrementAndGet() % 64 == 0) thumbnailDisk.trim()
  }

  private suspend fun <T> withProxyCancellation(
    proxy: NetworkStreamingProxy,
    streamId: String,
    block: suspend () -> T,
  ): T = coroutineScope {
    // A separate suspended child observes cancellation even when JNI is blocking an IO thread.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
      try { awaitCancellation() } finally { proxy.retireProbeRequests(streamId) }
    }
    try { block() } finally { watcher.cancel() }
  }

  fun cancelBackgroundThumbnailsExcept(
    retained: Collection<Pair<NetworkConnection, NetworkFile>>,
  ) {
    thumbnailDispatcher.cancelBackgroundExcept(retained.mapTo(mutableSetOf()) { "${cacheKey(it.first, it.second)}#${cacheToken(it.first, it.second)}" }, ThumbnailPriority.PREFETCH.ordinal)
  }

  /** Drops every queued or running preview so playback owns the network. */
  fun cancelThumbnails() {
    thumbnailDispatcher.cancelBackgroundExcept(emptySet(), 0)
  }

  /** Promote an already queued request without creating another native job. */
  fun promoteThumbnail(
    connection: NetworkConnection,
    file: NetworkFile,
    priority: ThumbnailPriority,
  ) {
    if (file.isDirectory) return
    thumbnailDispatcher.promote("${cacheKey(connection, file)}#${cacheToken(connection, file)}", priority.ordinal)
  }

  private data class ThumbnailResult(
    val bitmap: Bitmap,
    val usedTimestamp: Double,
    val usedHardware: Boolean,
  )

  /**
   * One hardware-decoded frame at the default timestamp covers the common case.
   * Failures are expensive (every attempt creates a fresh native instance), so the
   * remaining candidate timestamps are fetched together in one instance per decode
   * mode instead of one instance per timestamp.
   */
  private suspend fun generateThumbnailWithFallbacks(url: String): ThumbnailResult? {
    val primaryHardware = hardwareDecodeAttempts.first()
    singleThumbnail(url, thumbnailTimestamps.first(), primaryHardware)?.let { return it }
    for (useHwDec in hardwareDecodeAttempts) {
      val candidates =
        if (useHwDec == primaryHardware) thumbnailTimestamps.drop(1) else thumbnailTimestamps.toList()
      if (candidates.isEmpty()) continue
      val frames: List<Bitmap?>? = try {
        FastThumbnails.generateMultipleAsync(url, candidates, THUMBNAIL_SIZE, useHwDec = useHwDec)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.d(TAG, "Thumbnail batch decode failed hw=$useHwDec: ${e.message}")
        null
      }
      if (frames != null) {
        frames.forEachIndexed { index, bitmap ->
          if (bitmap != null && isUsableThumbnail(bitmap)) {
            return ThumbnailResult(bitmap, candidates.getOrElse(index) { thumbnailTimestamps.first() }, useHwDec)
          }
          bitmap?.recycle()
        }
        continue
      }
      // Batch decoding is an optimization only: fall back to one instance per
      // timestamp so a build without it behaves exactly as before.
      for (timestamp in candidates) {
        singleThumbnail(url, timestamp, useHwDec)?.let { return it }
      }
    }
    return null
  }

  private suspend fun singleThumbnail(url: String, timestamp: Double, useHwDec: Boolean): ThumbnailResult? {
    val bitmap = try {
      FastThumbnails.generateAsync(url, timestamp, THUMBNAIL_SIZE, useHwDec = useHwDec)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.d(TAG, "Thumbnail decode failed ts=${timestamp}s hw=$useHwDec: ${e.message}")
      null
    }
    if (bitmap != null && isUsableThumbnail(bitmap)) return ThumbnailResult(bitmap, timestamp, useHwDec)
    bitmap?.recycle()
    return null
  }

  /**
   * Reject frames that decoded but are clearly unusable (all-black / empty),
   * e.g. from a broken hardware-decoder output. Samples a small grid of pixels.
   */
  private fun isUsableThumbnail(bitmap: Bitmap): Boolean {
    if (bitmap.width <= 0 || bitmap.height <= 0) return false
    val xs = listOf(bitmap.width / 4, bitmap.width / 2, (bitmap.width * 3) / 4)
    val ys = listOf(bitmap.height / 4, bitmap.height / 2, (bitmap.height * 3) / 4)
    var blackSamples = 0
    var total = 0
    for (x in xs) {
      for (y in ys) {
        total++
        val pixel = bitmap.getPixel(x, y)
        val lum =
          ((pixel shr 16) and 0xFF) +
            ((pixel shr 8) and 0xFF) +
            (pixel and 0xFF)
        if (lum < 24) blackSamples++
      }
    }
    return blackSamples < total
  }

  /**
   * Check whether a thumbnail already exists in the disk cache.
   */
  suspend fun isThumbnailCached(connection: NetworkConnection, file: NetworkFile): Boolean {
    if (file.isDirectory) return false
    val key = cacheKey(connection, file)
    if (cacheGeneration.read { thumbnailCache.containsKey(key) }) return true
    return thumbnailDisk.transaction { exists(connection.id, key) }
  }

  /**
   * Invalidate the cached duration and thumbnail for a file so they are
   * re-generated on the next probe. Used by the manual "refresh cache"
   * long-press action in the network browser.
   */
  suspend fun invalidateCache(connection: NetworkConnection, file: NetworkFile) {
    invalidateCaches(connection, listOf(file))
  }

  /** One generation change/notification for a folder refresh, before any new probes start. */
  suspend fun invalidateCaches(connection: NetworkConnection, files: Collection<NetworkFile>) {
    val keysToPaths = files.associate { cacheKey(connection, it) to it.path }
    if (keysToPaths.isEmpty()) return
    thumbnailDisk.transaction {
      cacheGeneration.invalidate(connection.id, keysToPaths) {
        val editor = prefs.edit()
        keysToPaths.forEach { (key, path) ->
          versionIndex.remove(key)
          durationCache.remove(key)
          thumbnailCache.remove(key)
          resolutionCache.remove(key)
          keyIndex.remove("${connection.id}::$path")
          editor.remove(key).remove("${key}:w").remove("${key}:h")
        }
        editor.apply()
      }
      keysToPaths.keys.forEach { remove(connection.id, it) }
    }
  }

  /**
   * Pre-load durations for a set of files from the persistent cache only.
   * Returns a map of file path to cached duration (0 if not cached).
   */
  fun getCachedDurations(
    connection: NetworkConnection,
    files: List<NetworkFile>,
  ): Map<String, Long> =
    files.associate { file ->
      val key = cacheKey(connection, file)
      file.path to (durationCache[key] ?: prefs.getLong(key, 0L).takeIf { it > 0 } ?: 0L)
    }

  suspend fun clearCache() {
    check(appContext != null) { "NetworkMetadataProbe not initialized" }
    thumbnailDisk.transaction {
      cacheGeneration.clear {
        versionIndex.clear()
        durationCache.clear()
        thumbnailCache.clear()
        resolutionCache.clear()
        keyIndex.clear()
        prefs.edit().clear().apply()
      }
      clear()
    }
  }

  /**
   * Look up the exact cache key for a connection + file path. Used by the player
   * to resolve cached previews for proxy URLs, where only the path is known
   * (not size/etag/lastModified). Key format: <connId>::<path>::<size>::<etag>::<lm>.
   * Served from the selected-key index or the version index built at startup.
   */
  private fun findKey(
    connectionId: Long,
    filePath: String,
  ): String? {
    val indexLookup = "$connectionId::$filePath"
    keyIndex[indexLookup]?.let { return it }

    // Prefer a complete successful probe when legacy caches contain versions.
    val matchingKeys = versionIndex.keysFor(connectionId, filePath)
    val best =
      matchingKeys.firstOrNull { key ->
        prefs.getLong("${key}:w", 0L) > 0 && prefs.getLong("${key}:h", 0L) > 0
      } ?: matchingKeys.firstOrNull()
    if (best != null) {
      keyIndex[indexLookup] = best
    }
    return best
  }

  /**
   * Cached duration (ms) for a network file, matched by connection id + path.
   * Returns 0 when unknown.
   */
  fun getCachedDuration(
    connectionId: Long,
    filePath: String,
  ): Long {
    return cacheGeneration.read {
      val key = findKey(connectionId, filePath) ?: return@read 0L
      return@read durationCache[key] ?: prefs.getLong(key, 0L)
    }
  }

  /** Exact-version lookup used by active proxy streams. */
  fun getCachedDuration(
    connection: NetworkConnection,
    file: NetworkFile,
  ): Long {
    return cacheGeneration.read {
      val key = cacheKey(connection, file)
      val duration = durationCache[key] ?: prefs.getLong(key, 0L)
      if (duration > 0) versionIndex.add(key, connection.id, file.path)
      return@read duration
    }
  }

  /**
   * Cached resolution (width x height) for a network file, matched by
   * connection id + path, or null when unknown.
   */
  fun getCachedResolution(
    connectionId: Long,
    filePath: String,
  ): Pair<Int, Int>? {
    return cacheGeneration.read {
      val key = findKey(connectionId, filePath) ?: return@read null
      resolutionCache[key]?.let { return@read it }
      val w = prefs.getLong("${key}:w", 0L).toInt()
      val h = prefs.getLong("${key}:h", 0L).toInt()
      return@read if (w > 0 && h > 0) (w to h).also { resolutionCache[key] = it } else null
    }
  }

  /** Exact-version lookup used by active proxy streams. */
  fun getCachedResolution(
    connection: NetworkConnection,
    file: NetworkFile,
  ): Pair<Int, Int>? {
    return cacheGeneration.read {
      val key = cacheKey(connection, file)
      resolutionCache[key]?.let { return@read it }
      val w = prefs.getLong("${key}:w", 0L).toInt()
      val h = prefs.getLong("${key}:h", 0L).toInt()
      return@read if (w > 0 && h > 0) (w to h).also { resolutionCache[key] = it } else null
    }
  }

  /**
   * Cached thumbnail for a network file, matched by connection id + path, or
   * null if the preview was never generated.
   */
  suspend fun getCachedThumbnailBitmap(connectionId: Long, filePath: String): Bitmap? {
    val key = cacheGeneration.read { findKey(connectionId, filePath) } ?: return null
    return cachedThumbnail(key, connectionId)
  }

  /**
   * Clear all cached previews (durations + thumbnails, memory + disk) that
   * belong to a single connection, leaving other connections untouched.
   * Returns true on success; failures (including an uninitialized context)
   * are logged and reported instead of crashing.
   */
  suspend fun clearConnectionCache(connectionId: Long): Boolean {
    return try {
      check(appContext != null) { "NetworkMetadataProbe not initialized" }
      thumbnailDisk.transaction {
        cacheGeneration.invalidateConnection(connectionId) {
          val prefix = "$connectionId::"
          durationCache.keys.removeAll { it.startsWith(prefix) }
          thumbnailCache.keys.filter { it.startsWith(prefix) }.forEach { thumbnailCache.remove(it) }
          resolutionCache.keys.removeAll { it.startsWith(prefix) }
          keyIndex.keys.removeAll { it.startsWith(prefix) }
          val editor = prefs.edit()
          versionIndex.keysForConnection(connectionId).forEach { key ->
            editor.remove(key).remove("${key}:w").remove("${key}:h")
            versionIndex.remove(key)
          }
          editor.apply()
        }
        clearConnection(connectionId)
      }
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "clearConnectionCache failed for connection $connectionId", e)
      false
    }
  }
}
