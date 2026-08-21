package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.content.Context
import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.repository.NetworkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Pre-warms the duration and thumbnail cache for network connections marked with
 * "Refresh cache on app launch". Runs in the background after the app starts.
 *
 * Thumbnails are pre-generated only when the "Video Thumbnails" setting is
 * enabled, since generating them requires opening each file with mpv (which
 * downloads data). mpv uses HTTP Range requests so it only fetches the moov
 * index and the target frame - this is the most bandwidth-efficient method
 * available; reducing the thumbnail size would not lower network usage since
 * the encoded keyframe data must be downloaded regardless.
 *
 * Existing cache entries are kept (no re-download); only missing durations and
 * thumbnails are generated. This avoids first-open jank when browsing folders.
 */
object NetworkCacheWarmer {
  private const val TAG = "NetworkCacheWarmer"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var started = false

  fun start(context: Context) {
    if (started) return
    started = true

    val repository =
      org.koin.java.KoinJavaComponent.get<NetworkRepository>(NetworkRepository::class.java)

    scope.launch {
      // Let the app settle first so the preload doesn't compete with startup
      delay(3000)
      try {
        val connections = repository.getPreloadCacheConnections()
        if (connections.isEmpty()) {
          Log.d(TAG, "No connections marked for cache preloading")
          return@launch
        }
        connections.forEach { connection ->
          try {
            preloadConnection(repository, connection)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Error preloading ${connection.name}", e)
          }
        }
        Log.d(TAG, "Cache preloading finished")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Cache preloading failed", e)
      }
    }
  }

  private suspend fun preloadConnection(
    repository: NetworkRepository,
    connection: NetworkConnection,
  ) {
    Log.d(TAG, "Preloading cache for ${connection.name}")
    val rootPath = connection.path.ifBlank { "/" }

    // Connect first so listFiles reuses the active client
    repository.connect(connection)
      .onFailure { e ->
        Log.w(TAG, "Failed to connect to ${connection.name}: ${e.message}")
        return
      }

    // Only pre-generate thumbnails when the network thumbnail setting is enabled
    val showThumbnails =
      try {
        val preferences =
          org.koin.java.KoinJavaComponent.get<app.marlboroadvance.mpvex.preferences.AppearancePreferences>(
            app.marlboroadvance.mpvex.preferences.AppearancePreferences::class.java,
          )
        preferences.showNetworkThumbnails.get()
      } catch (e: Exception) {
        false
      }

    val videos = mutableListOf<NetworkFile>()
    collectVideos(
      repository = repository,
      connection = connection,
      path = rootPath,
      depth = 0,
      maxDepth = connection.preloadDepth.coerceAtLeast(1),
      perDir = connection.preloadPerDir.coerceAtLeast(0),
      maxTotal = connection.preloadTotal.coerceAtLeast(0),
      out = videos,
    )

    Log.d(TAG, "Preloading ${videos.size} videos from ${connection.name}")
    val targetVideos = videos.take(connection.preloadTotal.coerceAtLeast(0))
    if (targetVideos.isEmpty()) {
      Log.d(TAG, "No videos to preload for ${connection.name}")
      return
    }

    // Duration/resolution retrievers are independent and obey the connection's
    // configured concurrency. Native thumbnails remain in the single global
    // priority dispatcher and are intentionally never parallelized.
    val durationConcurrency = NetworkPreloadPolicy.clampThreads(connection.preloadThreads)
    val durationGate = Semaphore(durationConcurrency)
    coroutineScope {
      targetVideos.mapIndexed { index, file ->
        async {
          durationGate.withPermit {
            try {
              NetworkMetadataProbe.probeDuration(connection, file)
              if ((index + 1) % 20 == 0) {
                Log.d(TAG, "Probed ${index + 1}/${targetVideos.size} for ${connection.name}")
              }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Log.w(TAG, "Error probing ${file.name}", e)
            }
          }
        }
      }.awaitAll()
    }
    if (showThumbnails) {
      targetVideos.forEach { file ->
        try {
          NetworkMetadataProbe.probeThumbnail(
            connection,
            file,
            NetworkMetadataProbe.ThumbnailPriority.BACKGROUND,
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Error preloading thumbnail ${file.name}", e)
        }
      }
    }
    Log.d(TAG, "Done preloading ${connection.name} (${targetVideos.size} videos)")
  }

  /**
   * Collects videos up to the configured per-connection and per-directory limits.
   * [perDir] caps how many videos are taken from each directory (sub-directories
   * are still walked, subject to [maxDepth]); [maxTotal] caps the overall count.
   */
  private suspend fun collectVideos(
    repository: NetworkRepository,
    connection: NetworkConnection,
    path: String,
    depth: Int,
    maxDepth: Int,
    perDir: Int,
    maxTotal: Int,
    out: MutableList<NetworkFile>,
  ) {
    if (depth > maxDepth || out.size >= maxTotal) return

    val files =
      repository.listFiles(connection, path).getOrNull() ?: return

    var videosInThisDir = 0
    for (file in files) {
      if (out.size >= maxTotal) return
      if (file.isDirectory) {
        collectVideos(repository, connection, file.path, depth + 1, maxDepth, perDir, maxTotal, out)
      } else if (file.mimeType?.startsWith("video/") == true) {
        if (videosInThisDir >= perDir) continue
        videosInThisDir++
        out.add(file)
      }
    }
  }
}
