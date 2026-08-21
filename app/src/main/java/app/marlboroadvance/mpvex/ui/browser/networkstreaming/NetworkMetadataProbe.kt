package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

  private val durationCache = ConcurrentHashMap<String, Long>()
  private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()
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

  private data class ThumbnailRequest(
    val key: String,
    val connection: NetworkConnection,
    val file: NetworkFile,
    var priority: ThumbnailPriority,
    val result: CompletableDeferred<Bitmap?> = CompletableDeferred(),
  ) {
    var waiters: Int = 0
  }

  /** One native consumer shared by browser, playlist and startup pre-warm. */
  private object ThumbnailDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private val requests = ThumbnailPriorityQueue<String, ThumbnailPriority> { it.ordinal }
    private val byKey = mutableMapOf<String, ThumbnailRequest>()
    private var running: ThumbnailRequest? = null
    private var runningJob: Job? = null

    init {
      scope.launch {
        for (ignored in wakeUp) {
          while (true) {
            val request = synchronized(lock) {
              requests.poll()?.let { entry ->
                byKey[entry.key]?.also { running = it }
              }
            } ?: break

            // Dequeue and job assignment are separate operations because the
            // native call must run in a child Job. Re-check the shared request
            // under the same lock before creating that Job; a last waiter may
            // have cancelled in the gap.
            val claimed = synchronized(lock) {
              val canStart = ThumbnailDispatchGuard.canStart(
                ThumbnailDispatchSnapshot(
                  waiters = request.waiters,
                  resultCancelled = request.result.isCancelled,
                  stillRegistered = byKey[request.key] === request,
                ),
              )
              if (!canStart) {
                byKey.remove(request.key, request)
                if (running === request) running = null
              }
              canStart
            }
            if (!claimed) {
              request.result.cancel()
              continue
            }

            // Start lazily so cancellation can never race assignment of the
            // active job. The assignment gate below handles cancellation that
            // arrives after this child is created but before it is started.
            val job = launch(start = CoroutineStart.LAZY) {
              val canEnterNative = synchronized(lock) {
                ThumbnailDispatchGuard.canStart(
                  ThumbnailDispatchSnapshot(
                    waiters = request.waiters,
                    resultCancelled = request.result.isCancelled,
                    stillRegistered = byKey[request.key] === request,
                  ),
                )
              }
              if (!canEnterNative || !currentCoroutineContext().isActive) {
                request.result.cancel()
                return@launch
              }
              try {
                request.result.complete(probeThumbnailNow(request.connection, request.file))
              } catch (e: CancellationException) {
                request.result.cancel(e)
              } catch (e: Exception) {
                Log.w(TAG, "Thumbnail request failed for ${request.file.path}", e)
                request.result.complete(null)
              }
            }

            val assigned = synchronized(lock) {
              val canStart = ThumbnailDispatchGuard.canStart(
                ThumbnailDispatchSnapshot(
                  waiters = request.waiters,
                  resultCancelled = request.result.isCancelled,
                  stillRegistered = byKey[request.key] === request,
                ),
              )
              if (canStart) {
                runningJob = job
              }
              canStart
            }
            if (!assigned) {
              request.result.cancel()
              job.cancel()
              job.join()
              synchronized(lock) {
                byKey.remove(request.key, request)
                if (running === request) running = null
                if (runningJob === job) runningJob = null
              }
              continue
            }

            job.start()
            job.join()
            synchronized(lock) {
              byKey.remove(request.key, request)
              running = null
              runningJob = null
            }
          }
        }
      }
    }

    suspend fun submit(
      key: String,
      connection: NetworkConnection,
      file: NetworkFile,
      priority: ThumbnailPriority,
    ): Bitmap? {
      val request = synchronized(lock) {
        val existing = byKey[key]
        val selected = if (existing != null && !existing.result.isCancelled) {
          if (existing !== running && priority.ordinal < existing.priority.ordinal) {
            requests.promote(existing.key, priority)
            existing.priority = priority
          }
          existing
        } else {
          val request = ThumbnailRequest(key, connection, file, priority)
          byKey[key] = request
          requests.offer(key, priority)
          wakeUp.trySend(Unit)
          request
        }
        selected.also { it.waiters++ }
      }
      return try {
        request.result.await()
      } finally {
        // A cancelled screen must be able to withdraw queued work. Shared
        // requests stay alive until their last waiter leaves, so a playlist
        // sheet and a browser card can safely deduplicate the same thumbnail.
        if (!currentCoroutineContext().isActive) {
          synchronized(lock) {
            request.waiters = (request.waiters - 1).coerceAtLeast(0)
            if (request.waiters == 0 && !request.result.isCompleted) {
              if (request === running) {
                // A worker can dequeue just before assigning runningJob. Mark
                // the shared result cancelled in that tiny window so it will
                // not start native decoding after its last waiter disappears.
                runningJob?.cancel() ?: request.result.cancel()
              } else if (requests.cancel(request.key)) {
                byKey.remove(request.key, request)
                request.result.cancel()
              }
            }
          }
        } else {
          synchronized(lock) {
            request.waiters = (request.waiters - 1).coerceAtLeast(0)
          }
        }
      }
    }

    fun promote(key: String, priority: ThumbnailPriority) {
      synchronized(lock) {
        val request = byKey[key] ?: return
        if (priority.ordinal >= request.priority.ordinal) return
        if (request === running || requests.promote(key, priority)) {
          request.priority = priority
        }
      }
    }

    fun cancelBackgroundExcept(retainedKeys: Set<String>) {
      synchronized(lock) {
        requests.entries()
          .filter { it.priority.ordinal >= ThumbnailPriority.PREFETCH.ordinal && it.key !in retainedKeys }
          .forEach { entry ->
            requests.cancel(entry.key)
            byKey.remove(entry.key)?.result?.cancel()
          }
        val active = running
        if (active != null && active.priority.ordinal >= ThumbnailPriority.PREFETCH.ordinal && active.key !in retainedKeys) {
          runningJob?.cancel() ?: active.result.cancel()
        }
      }
    }
  }
  // MediaMetadataRetriever instances are independent and truly parallel, so
  // durations keep a higher concurrency for real gains.
  private val durationSemaphore = Semaphore(6)

  @Volatile
  private var appContext: Context? = null

  fun init(context: Context) {
    appContext = context.applicationContext
  }

  private val prefs by lazy {
    appContext!!.getSharedPreferences("network_metadata_cache", Context.MODE_PRIVATE)
  }

  private val thumbnailDir: File by lazy {
    File(appContext!!.filesDir, "network_thumbnails").apply { mkdirs() }
  }

  // Thumbnails are stored per connection under network_thumbnails/<connectionId>/
  // so the whole set for one connection can be purged without touching others.
  private fun thumbnailFile(key: String, connection: NetworkConnection): File {
    val dir = File(thumbnailDir, connection.id.toString()).apply { mkdirs() }
    return File(dir, keyToFileName(key))
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

    // Memory cache
    durationCache[key]?.let { return it }

    // Persistent cache (SharedPreferences)
    prefs.getLong(key, 0L).takeIf { it > 0 }?.let { cached ->
      durationCache[key] = cached
      return cached
    }

    // Probe over the network (MediaMetadataRetriever only reads the container
    // header to extract the duration and resolution - minimal data transfer).
    val result =
      withContext(Dispatchers.IO) {
        durationSemaphore.withPermit {
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

    if (result.duration > 0 || (result.width > 0 && result.height > 0)) {
      keyIndex["${connection.id}::${file.path}"] = key
      // Remove stale entries for the same path (old size/etag/lastModified
      // versions may hold bogus durations from failed probes).
      val prefix = "${connection.id}::"
      val needle = "::${file.path}::"
      prefs.all.keys
        .filter { isCanonicalCacheKey(it) && it.startsWith(prefix) && it.contains(needle) && it != key }
        .forEach { stale ->
          prefs.edit().remove(stale).remove("${stale}:w").remove("${stale}:h").apply()
          runCatching { thumbnailFile(stale, connection).delete() }
        }
      durationCache.keys
        .filter { isCanonicalCacheKey(it) && it.startsWith(prefix) && it.contains(needle) && it != key }
        .forEach { durationCache.remove(it) }
      thumbnailCache.keys
        .filter { isCanonicalCacheKey(it) && it.startsWith(prefix) && it.contains(needle) && it != key }
        .forEach { thumbnailCache.remove(it) }
      resolutionCache.keys
        .filter { isCanonicalCacheKey(it) && it.startsWith(prefix) && it.contains(needle) && it != key }
        .forEach { resolutionCache.remove(it) }
      val editor = prefs.edit()
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
    return result.duration
  }

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

    // Memory cache
    thumbnailCache[key]?.let { return it }

    // Disk cache
    val diskFile = thumbnailFile(key, connection)
    if (diskFile.exists()) {
      val cached = runCatching {
        BitmapFactory.decodeFile(diskFile.absolutePath)
      }.getOrNull()
      if (cached != null) {
        thumbnailCache[key] = cached
        return cached
      }
    }

    return ThumbnailDispatcher.submit(key, connection, file, priority)
  }

  private suspend fun probeThumbnailNow(
    connection: NetworkConnection,
    file: NetworkFile,
  ): Bitmap? {
    val key = cacheKey(connection, file)
    thumbnailCache[key]?.let { return it }
    val diskFile = thumbnailFile(key, connection)
    if (diskFile.exists()) {
      BitmapFactory.decodeFile(diskFile.absolutePath)?.let {
        thumbnailCache[key] = it
        return it
      }
    }
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
            generateThumbnailWithFallbacks(url)
          } finally {
            proxy.unregisterStream(streamId)
          }
      }

    val bitmap = result?.bitmap
    if (bitmap != null) {
      thumbnailCache[key] = bitmap
      keyIndex["${connection.id}::${file.path}"] = key
      val compressed = runCatching {
        val format = if (Build.VERSION.SDK_INT >= 30) {
          Bitmap.CompressFormat.WEBP_LOSSY
        } else {
          Bitmap.CompressFormat.WEBP
        }
        FileOutputStream(diskFile).use { out ->
          bitmap.compress(format, THUMBNAIL_JPEG_QUALITY, out)
          out.flush()
        }
        true
      }.getOrDefault(false)

      // Fallback to JPEG if WebP encoding was not possible
      if (!compressed) {
        runCatching {
          FileOutputStream(diskFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, out)
            out.flush()
          }
        }
      }
    }
    return bitmap
  }

  fun cancelBackgroundThumbnailsExcept(
    retained: Collection<Pair<NetworkConnection, NetworkFile>>,
  ) {
    ThumbnailDispatcher.cancelBackgroundExcept(retained.mapTo(mutableSetOf()) { cacheKey(it.first, it.second) })
  }

  /** Promote an already queued request without creating another native job. */
  fun promoteThumbnail(
    connection: NetworkConnection,
    file: NetworkFile,
    priority: ThumbnailPriority,
  ) {
    if (file.isDirectory) return
    ThumbnailDispatcher.promote(cacheKey(connection, file), priority)
  }

  private data class ThumbnailResult(
    val bitmap: Bitmap,
    val usedTimestamp: Double,
    val usedHardware: Boolean,
  )

  /**
   * Generate a thumbnail trying hardware decode first, then software, and
   * advancing through candidate timestamps only when the current frame is
   * unusable (null or detected as black/empty).
   */
  private suspend fun generateThumbnailWithFallbacks(url: String): ThumbnailResult? {
    for (timestamp in thumbnailTimestamps) {
      for (useHwDec in hardwareDecodeAttempts) {
        val bitmap = try {
          FastThumbnails.generateAsync(url, timestamp, THUMBNAIL_SIZE, useHwDec = useHwDec)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.d(TAG, "Thumbnail decode failed ts=${timestamp}s hw=$useHwDec: ${e.message}")
          null
        }
        if (bitmap != null && isUsableThumbnail(bitmap)) {
          return ThumbnailResult(bitmap, timestamp, useHwDec)
        }
        // Invalid frame: recycle and try the next candidate
        bitmap?.recycle()
      }
    }
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
   * Pre-fetch bounded head and tail segments. The tail covers MP4 files whose
   * moov/index is not fast-started and other containers with end indexes.
   */
  suspend fun prefetchHeader(
    connection: NetworkConnection,
    file: NetworkFile,
  ): Boolean {
    if (file.isDirectory) return false
    val proxy = NetworkStreamingProxy.getInstance()
    val streamId = "prefetch_${connection.id}_${System.nanoTime()}"
    val url =
      proxy.registerStream(
        streamId = streamId,
        connection = connection,
        file = file,
      )
    return try {
      try {
        val client = okhttp3.OkHttpClient.Builder()
          .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
          .build()
        val segmentSize = minOf(
          file.size.takeIf { it > 0 } ?: (2L * 1024 * 1024),
          2L * 1024 * 1024,
        )
        suspend fun fetch(start: Long, end: Long): Boolean {
          if (start < 0L || end < start) return false
          val request = okhttp3.Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
          return client.newCall(request).execute().use { response ->
            if (response.code != 206) return@use false
            val contentRange = response.header("Content-Range") ?: return@use false
            val range = contentRange.removePrefix("bytes ").split('-', limit = 2)
            val returnedStart = range.getOrNull(0)?.toLongOrNull()
            val returnedEnd = range.getOrNull(1)?.substringBefore('/')?.toLongOrNull()
            returnedStart == start && returnedEnd != null && returnedEnd in start..end
          }
        }
        val headOk = fetch(0L, segmentSize - 1)
        val tailStart = (file.size - segmentSize).coerceAtLeast(0L)
        val tailOk = if (file.size > segmentSize && tailStart > 0L) {
          fetch(tailStart, file.size - 1)
        } else {
          true
        }
        headOk && tailOk
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Header prefetch failed for ${file.path}", e)
        false
      }
    } finally {
      proxy.unregisterStream(streamId)
    }
  }

  /**
   * Check whether a thumbnail already exists in the disk cache.
   */
  fun isThumbnailCached(
    connection: NetworkConnection,
    file: NetworkFile,
  ): Boolean {
    if (file.isDirectory) return false
    val key = cacheKey(connection, file)
    if (thumbnailCache.containsKey(key)) return true
    return thumbnailFile(key, connection).exists()
  }

  /**
   * Invalidate the cached duration and thumbnail for a file so they are
   * re-generated on the next probe. Used by the manual "refresh cache"
   * long-press action in the network browser.
   */
  fun invalidateCache(
    connection: NetworkConnection,
    file: NetworkFile,
  ) {
    val key = cacheKey(connection, file)
    durationCache.remove(key)
    thumbnailCache.remove(key)
    resolutionCache.remove(key)
    keyIndex.remove("${connection.id}::${file.path}")
    prefs.edit().remove(key).remove("${key}:w").remove("${key}:h").apply()
    runCatching {
      thumbnailFile(key, connection).delete()
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

  fun clearCache() {
    if (appContext == null) {
      Log.e(TAG, "clearCache: NetworkMetadataProbe not initialized (appContext null)")
      return
    }
    durationCache.clear()
    thumbnailCache.clear()
    resolutionCache.clear()
    keyIndex.clear()
    prefs.edit().clear().apply()
    runCatching {
      thumbnailDir.deleteRecursively()
    }
  }

  /**
   * Look up the exact cache key for a connection + file path. Used by the player
   * to resolve cached previews for proxy URLs, where only the path is known
   * (not size/etag/lastModified). Key format: <connId>::<path>::<size>::<etag>::<lm>.
   * Served from an O(1) in-memory index; falls back to a scan only on a cold
   * start, then back-fills the index.
   */
  private fun findKey(
    connectionId: Long,
    filePath: String,
  ): String? {
    val indexLookup = "$connectionId::$filePath"
    keyIndex[indexLookup]?.let { return it }

    val prefix = "$connectionId::"
    val needle = "::${filePath}::"
    // A file can have multiple cache keys when size/etag/lastModified changed,
    // leaving stale entries behind (e.g. an old failed probe cached a bogus
    // short duration without resolution). Prefer the key with resolution data,
    // which is the product of a complete successful probe.
    val matchingKeys =
      (prefs.all.keys.filter { isCanonicalCacheKey(it) && it.startsWith(prefix) && it.contains(needle) } +
        durationCache.keys.filter { isCanonicalCacheKey(it) && it.startsWith(prefix) && it.contains(needle) })
        .distinct()
    val best =
      matchingKeys.firstOrNull { key ->
        prefs.getLong("${key}:w", 0L) > 0 && prefs.getLong("${key}:h", 0L) > 0
      } ?: matchingKeys.firstOrNull()
    if (best != null) {
      keyIndex[indexLookup] = best
    }
    return best
  }

  private fun isCanonicalCacheKey(key: String): Boolean =
    !key.endsWith(":w") && !key.endsWith(":h")

  private fun indexKey(
    connection: NetworkConnection,
    file: NetworkFile,
  ): String {
    val key = cacheKey(connection, file)
    keyIndex["${connection.id}::${file.path}"] = key
    return key
  }

  /**
   * Cached duration (ms) for a network file, matched by connection id + path.
   * Returns 0 when unknown.
   */
  fun getCachedDuration(
    connectionId: Long,
    filePath: String,
  ): Long {
    val key = findKey(connectionId, filePath) ?: return 0L
    return durationCache[key] ?: prefs.getLong(key, 0L)
  }

  /** Exact-version lookup used by active proxy streams. */
  fun getCachedDuration(
    connection: NetworkConnection,
    file: NetworkFile,
  ): Long {
    val key = cacheKey(connection, file)
    return durationCache[key] ?: prefs.getLong(key, 0L)
  }

  /**
   * Cached resolution (width x height) for a network file, matched by
   * connection id + path, or null when unknown.
   */
  fun getCachedResolution(
    connectionId: Long,
    filePath: String,
  ): Pair<Int, Int>? {
    val key = findKey(connectionId, filePath) ?: return null
    resolutionCache[key]?.let { return it }
    val w = prefs.getLong("${key}:w", 0L).toInt()
    val h = prefs.getLong("${key}:h", 0L).toInt()
    return if (w > 0 && h > 0) (w to h).also { resolutionCache[key] = it } else null
  }

  /** Exact-version lookup used by active proxy streams. */
  fun getCachedResolution(
    connection: NetworkConnection,
    file: NetworkFile,
  ): Pair<Int, Int>? {
    val key = cacheKey(connection, file)
    resolutionCache[key]?.let { return it }
    val w = prefs.getLong("${key}:w", 0L).toInt()
    val h = prefs.getLong("${key}:h", 0L).toInt()
    return if (w > 0 && h > 0) (w to h).also { resolutionCache[key] = it } else null
  }

  /**
   * Cached thumbnail for a network file, matched by connection id + path, or
   * null if the preview was never generated.
   */
  fun getCachedThumbnailBitmap(
    connectionId: Long,
    filePath: String,
  ): Bitmap? {
    val key = findKey(connectionId, filePath) ?: return null
    thumbnailCache[key]?.let { return it }
    val file = File(File(thumbnailDir, connectionId.toString()), keyToFileName(key))
    if (file.exists()) {
      val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
      if (bmp != null) thumbnailCache[key] = bmp
      return bmp
    }
    return null
  }

  /**
   * Clear all cached previews (durations + thumbnails, memory + disk) that
   * belong to a single connection, leaving other connections untouched.
   * Returns true on success; failures (including an uninitialized context)
   * are logged and reported instead of crashing.
   */
  fun clearConnectionCache(connectionId: Long): Boolean {
    return try {
      val ctx = appContext
      if (ctx == null) {
        Log.e(TAG, "clearConnectionCache: NetworkMetadataProbe not initialized (appContext null)")
        return false
      }
      val prefix = "$connectionId::"
      durationCache.keys.removeAll { it.startsWith(prefix) }
      thumbnailCache.keys.removeAll { it.startsWith(prefix) }
      resolutionCache.keys.removeAll { it.startsWith(prefix) }
      keyIndex.keys.removeAll { it.startsWith(prefix) }
      prefs.all.keys
        .filter { it.startsWith(prefix) }
        .forEach { key -> prefs.edit().remove(key).apply() }
      runCatching {
        File(thumbnailDir, connectionId.toString()).deleteRecursively()
      }
      true
    } catch (e: Exception) {
      Log.e(TAG, "clearConnectionCache failed for connection $connectionId", e)
      false
    }
  }

  private fun keyToFileName(key: String): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(key.toByteArray())
    val hex = digest.joinToString("") { b -> "%02x".format(b) }
    return "$hex.webp"
  }
}
