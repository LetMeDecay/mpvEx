package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.NetworkSortType
import app.marlboroadvance.mpvex.preferences.SortOrder
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.utils.sort.SortUtils
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap

/**
 * ViewModel for browsing files on a network share
 * Follows MVVM pattern with proper separation of concerns
 */
class NetworkBrowserViewModel(
  private val application: Application,
  private val connectionId: Long,
  private val currentPath: String,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: NetworkRepository by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()

  private val _files = MutableStateFlow<List<NetworkFile>>(emptyList())
  val files: StateFlow<List<NetworkFile>> = _files.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  // Thumbnails keyed by file path
  private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
  val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

  // Per-path thumbnail jobs so queued prefetch work can be cancelled when the
  // user scrolls past. Jobs that already entered the decode stage are kept to
  // avoid killing an in-flight request and re-downloading it moments later.
  private val thumbnailJobs = ConcurrentHashMap<String, Job>()
  private val runningThumbnails = ConcurrentHashMap<String, Job>()
  private val thumbnailPriorities = ConcurrentHashMap<String, NetworkMetadataProbe.ThumbnailPriority>()
  private val thumbnailLock = Any()
  private var directoryRevision = 0L
  private var directoryLoadJob: Job? = null
  private var durationProbeJob: Job? = null
  @Volatile private var listedFilesByPath: Map<String, NetworkFile> = emptyMap()
  private var retainedThumbnailPaths: Set<String> = emptySet()
  private val thumbnailVersions = ConcurrentHashMap<String, Pair<NetworkFile, Long>>()
  @Volatile
  private var loadedConnection: NetworkConnection? = null

  // Per-folder sort settings. Each directory keeps its own sort rule,
  // persisted in SharedPreferences keyed by connectionId + folder path.
  private val sortPrefs =
    application.getSharedPreferences("network_folder_sort", android.content.Context.MODE_PRIVATE)

  private val sortKey: String get() = "${connectionId}_${currentPath}"

  private val _sortType = MutableStateFlow<NetworkSortType>(loadSortType())
  val sortType: StateFlow<NetworkSortType> = _sortType.asStateFlow()

  private val _sortOrder = MutableStateFlow<SortOrder>(loadSortOrder())
  val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

  private fun loadSortType(): NetworkSortType =
    sortPrefs
      .getString("sort_type_$sortKey", null)
      ?.let { stored -> NetworkSortType.entries.firstOrNull { it.name == stored } }
      ?: NetworkSortType.Title

  private fun loadSortOrder(): SortOrder =
    if (sortPrefs.getBoolean("sort_order_asc_$sortKey", true)) SortOrder.Ascending else SortOrder.Descending

  fun setSortType(type: NetworkSortType) {
    _sortType.value = type
    sortPrefs.edit().putString("sort_type_$sortKey", type.name).apply()
  }

  fun setSortOrder(order: SortOrder) {
    _sortOrder.value = order
    sortPrefs.edit().putBoolean("sort_order_asc_$sortKey", order.isAscending).apply()
  }

  init {
    // Initialize the persistent metadata probe cache
    NetworkMetadataProbe.init(application)

    viewModelScope.launch {
      var seenRevision = NetworkMetadataProbe.cacheChanges.value.revision
      NetworkMetadataProbe.cacheChanges.collect { snapshot ->
        val affected = snapshot.pathsSince(seenRevision, connectionId)
        seenRevision = snapshot.revision
        val connection = loadedConnection ?: return@collect
        if (affected != null && affected.isEmpty()) return@collect
        val targets = if (affected == null) listedFilesByPath.values.toList()
          else affected.mapNotNull(listedFilesByPath::get)
        if (targets.isEmpty()) return@collect
        val targetPaths = targets.mapTo(mutableSetOf()) { it.path }
        _thumbnails.update { cached ->
          cached.filterKeys { path ->
            path !in targetPaths || thumbnailVersions[path]?.let { (file, token) ->
              NetworkMetadataProbe.cacheToken(connection, file) == token
            } == true
          }
        }
        // Preferences are initialized at startup; only affected files need a memory lookup.
        val durations = targets.associate { it.path to NetworkMetadataProbe.getCachedDuration(connection, it) }
        _files.update { current ->
          var changed = false
          val updated = current.map { file ->
            val duration = durations[file.path]
            if (duration != null && duration != file.duration) {
              changed = true
              file.copy(duration = duration)
            } else file
          }
          if (changed) updated else current
        }
      }
    }

    // Apply sorting whenever files or this folder's sort settings change
    viewModelScope.launch {
      combine(
        _files,
        _sortType,
        _sortOrder,
      ) { fileList, sortType, sortOrder ->
        SortUtils.sortNetworkFiles(fileList, sortType, sortOrder)
      }.collectLatest { sortedFiles ->
        _files.value = sortedFiles
      }
    }
  }

  /**
   * Load files in the current directory
   */
  fun loadFiles() {
    val revision = ++directoryRevision
    directoryLoadJob?.cancel()
    directoryLoadJob = viewModelScope.launch {
      _isLoading.value = true
      _error.value = null

      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception(application.getString(R.string.network_connection_not_found))
        val listing = repository.listFiles(connection, currentPath)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        listing.onSuccess { fileList ->
            synchronized(thumbnailLock) {
              val next = fileList.associateBy { it.path }
              val obsolete = (listedFilesByPath.keys + thumbnailVersions.keys).filter { path ->
                loadedConnection != connection || listedFilesByPath[path]?.sameVersion(next[path]) != true
              }.toSet()
              listedFilesByPath = next
              loadedConnection = connection
              obsolete.forEach { path ->
                thumbnailJobs.remove(path)?.cancel()
                thumbnailPriorities.remove(path)
                thumbnailVersions.remove(path)
              }
              _thumbnails.update { cached -> cached.filterKeys { it !in obsolete } }
            }
            // Pre-load cached durations so the list displays instantly without re-probing
            val cachedDurations = NetworkMetadataProbe.getCachedDurations(connection, fileList)
            _files.value = fileList.map { file ->
              file.copy(
                duration = cachedDurations[file.path]?.takeIf { it > 0 } ?: file.duration,
              )
            }
            probeMissingDurations(connection, fileList)
          }
          .onFailure { e ->
            _error.value = e.message ?: application.getString(R.string.network_unknown_error)
          }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        _error.value = e.message ?: application.getString(R.string.network_unknown_error)
      } finally {
        if (revision == directoryRevision) _isLoading.value = false
      }
    }
  }

  /**
   * Reload current directory to trigger thumbnail generation for all videos.
   * Called when the "Video Thumbnails" option is enabled in the sort & view dialog.
   */
  fun reloadWithThumbnails() {
    val currentFiles = _files.value
    if (currentFiles.isEmpty()) {
      loadFiles()
      return
    }
    currentFiles.filter { it.path in retainedThumbnailPaths }.forEach { file ->
      ensureThumbnail(file, NetworkMetadataProbe.ThumbnailPriority.PREFETCH)
    }
  }

  /**
   * Probe durations in the background for video files that are not yet cached.
   * Thumbnails are NOT probed here - they are loaded lazily via [ensureThumbnail]
   * when the video item becomes visible, to avoid downloading data for the whole
   * directory at once.
   */
  private fun probeMissingDurations(
    connection: NetworkConnection,
    fileList: List<NetworkFile>,
  ) {
    durationProbeJob?.cancel()
    val videos = SortUtils.sortNetworkFiles(fileList, _sortType.value, _sortOrder.value).filter {
      !it.isDirectory && it.mimeType?.startsWith("video/") == true && it.duration <= 0
    }
    if (videos.isEmpty()) return

    durationProbeJob = viewModelScope.launch(Dispatchers.IO) {
      videos.forEachNetworkItem(NetworkPreloadPolicy.MAX_THREADS) { file ->
        try {
          val generation = NetworkMetadataProbe.cacheToken(connection, file)
          val duration = NetworkMetadataProbe.probeDuration(connection, file)
          if (duration > 0) {
            withContext(Dispatchers.Main) {
              if (!file.sameVersion(listedFilesByPath[file.path]) || NetworkMetadataProbe.cacheToken(connection, file) != generation) return@withContext
              _files.update { current ->
                if (current.any { it.path == file.path && it.duration != duration }) {
                  current.map {
                    if (it.path == file.path) {
                      it.copy(
                        duration = duration.takeIf { value -> value > 0 } ?: it.duration,
                      )
                    } else it
                  }
                } else {
                  current
                }
              }
            }
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Error probing duration for ${file.name}", e)
        }
      }
    }
  }

  /**
   * Lazily generate a thumbnail for a single video file.
   * Only called for visible (or nearby prefetched) items, so network data is
   * only downloaded for videos the user actually sees. Cached results are
   * returned immediately. Jobs are tracked so distant prefetch tasks can be
   * cancelled while scrolling fast ([cancelThumbnailsExcept]).
   */
  fun ensureThumbnail(
    file: NetworkFile,
    priority: NetworkMetadataProbe.ThumbnailPriority =
      NetworkMetadataProbe.ThumbnailPriority.VISIBLE,
  ) {
    if (file.isDirectory) return
    if (!appearancePreferences.showNetworkThumbnails.get()) return
    if (!file.sameVersion(listedFilesByPath[file.path])) return
    if (_thumbnails.value.containsKey(file.path)) return

    synchronized(thumbnailLock) {
      val existing = thumbnailJobs[file.path]
      if (existing != null && !existing.isCompleted && !existing.isCancelled) {
        val effectivePriority =
          thumbnailPriorities.compute(file.path) { _, previous ->
            if (previous == null || priority.ordinal < previous.ordinal) priority else previous
          } ?: priority
        // The job itself is shared. Re-submit is deliberately avoided; this
        // promotion reaches the central dispatcher and can reorder a queued
        // PREFETCH request as CURRENT/VISIBLE without another native decode.
        loadedConnection?.let { connection ->
          NetworkMetadataProbe.promoteThumbnail(connection, file, effectivePriority)
        }
        return
      }
      if (existing != null) {
        thumbnailJobs.remove(file.path, existing)
        thumbnailPriorities.remove(file.path)
      }

      thumbnailPriorities[file.path] = priority
      val job =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          try {
            val connection = repository.getConnectionById(connectionId)
              ?: return@launch
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.let { runningJob ->
              runningThumbnails[file.path] = runningJob
            }
            try {
              // Read the latest priority immediately before submitting. A
              // visible card can promote a queued prefetch while this job is
              // waiting for the repository lookup.
              val effectivePriority = synchronized(thumbnailLock) {
                thumbnailPriorities[file.path] ?: priority
              }
              val generation = NetworkMetadataProbe.cacheToken(connection, file)
              val bitmap = NetworkMetadataProbe.probeThumbnail(connection, file, effectivePriority)
              if (bitmap != null && NetworkMetadataProbe.cacheToken(connection, file) == generation) {
                retainThumbnail(connection, file, bitmap, generation)
              }
            } finally {
              runningThumbnails.remove(file.path, kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job])
            }
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Error probing thumbnail for ${file.name}", e)
          } finally {
            // Remove only this coroutine's entry. A cancelled request can
            // finish after a new request for the same path is installed.
            val self = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            if (self != null && thumbnailJobs.remove(file.path, self)) {
              thumbnailPriorities.remove(file.path)
            }
          }
        }
      thumbnailJobs[file.path] = job
      job.start()
    }
  }

  /**
   * Cancel queued (not yet decoding) thumbnail jobs whose file is outside the
   * current visible+prefetch window. In-flight decodes are left alone so we
   * never waste an already-downloaded range and re-fetch it on scroll-back.
   */
  private fun retainThumbnail(connection: NetworkConnection, file: NetworkFile, bitmap: Bitmap, generation: Long) {
    synchronized(thumbnailLock) {
      if (connection != loadedConnection || file.path !in retainedThumbnailPaths ||
        !file.sameVersion(listedFilesByPath[file.path]) ||
        NetworkMetadataProbe.cacheToken(connection, file) != generation) return
      thumbnailVersions[file.path] = file to generation
      _thumbnails.update { it + (file.path to bitmap) }
    }
  }

  fun cancelThumbnailsExcept(keepPaths: Set<String>) {
    retainedThumbnailPaths = keepPaths
    thumbnailVersions.keys.retainAll(keepPaths)
    _thumbnails.update { cached -> cached.filterKeys { it in keepPaths } }
    thumbnailJobs.keys.forEach { path ->
      if (path !in keepPaths && !runningThumbnails.containsKey(path)) {
        synchronized(thumbnailLock) {
          val job = thumbnailJobs.remove(path)
          if (job != null) {
            thumbnailPriorities.remove(path)
            job.cancel()
          }
        }
      }
    }
    loadedConnection?.let { connection ->
      NetworkMetadataProbe.cancelBackgroundThumbnailsExcept(
        _files.value
          .filter { !it.isDirectory && it.path in keepPaths }
          .map { connection to it },
      )
    }
  }

  fun releaseBrowserThumbnails() {
    retainedThumbnailPaths = emptySet()
    thumbnailVersions.clear()
    _thumbnails.value = emptyMap()
    synchronized(thumbnailLock) {
      val pending = thumbnailJobs.values.toList()
      thumbnailJobs.clear()
      thumbnailPriorities.clear()
      pending.forEach { it.cancel() }
    }
  }

  /**
   * Manually refresh the cache for a file or an entire folder (recursively).
   * Invalidate existing cache entries, then re-generate duration and thumbnails.
   * Triggered by long-pressing an item in the network browser.
   */
  fun refreshCache(item: NetworkFile) {
    viewModelScope.launch(Dispatchers.IO) {
      val connection = repository.getConnectionById(connectionId)
        ?: return@launch

      if (item.isDirectory) {
        refreshFolderCache(connection, item.path)
      } else {
        refreshFileCache(connection, item)
      }
    }
  }

  private suspend fun refreshFileCache(
    connection: NetworkConnection,
    file: NetworkFile,
  ) {
    try {
      NetworkMetadataProbe.invalidateCache(connection, file)
      val generation = NetworkMetadataProbe.cacheToken(connection, file)
      withContext(Dispatchers.Main) {
        thumbnailJobs.remove(file.path)?.cancel()
        thumbnailVersions.remove(file.path)
        _thumbnails.update { it - file.path }
      }
      val duration = NetworkMetadataProbe.probeDuration(connection, file)
      withContext(Dispatchers.Main) {
        if (!file.sameVersion(listedFilesByPath[file.path]) || NetworkMetadataProbe.cacheToken(connection, file) != generation) return@withContext
        _files.value = _files.value.map {
          if (it.path == file.path) it.copy(duration = duration) else it
        }
      }
      if (appearancePreferences.showNetworkThumbnails.get()) {
        val bitmap = NetworkMetadataProbe.probeThumbnail(connection, file)
        if (bitmap != null) {
          withContext(Dispatchers.Main) {
            if (!file.sameVersion(listedFilesByPath[file.path]) || NetworkMetadataProbe.cacheToken(connection, file) != generation) return@withContext
            retainThumbnail(connection, file, bitmap, generation)
          }
        }
      }
      Log.i(TAG, "Refreshed cache for ${file.name} (${duration}ms)")
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Error refreshing cache for ${file.name}", e)
    }
  }

  private suspend fun refreshFolderCache(
    connection: NetworkConnection,
    folderPath: String,
  ) {
    try {
      val videos = mutableListOf<NetworkFile>()
      collectVideosRecursively(connection, folderPath, 0, videos)
      if (videos.isEmpty()) return

      val showThumbnails = appearancePreferences.showNetworkThumbnails.get()

      NetworkMetadataProbe.invalidateCaches(connection, videos)
      videos.forEachNetworkItem(NetworkPreloadPolicy.MAX_THREADS) { file ->
        try {
          val generation = NetworkMetadataProbe.cacheToken(connection, file)
          val newDuration = NetworkMetadataProbe.probeDuration(connection, file)
          val bitmap = if (showThumbnails) NetworkMetadataProbe.probeThumbnail(connection, file) else null
          withContext(Dispatchers.Main) {
            if (!file.sameVersion(listedFilesByPath[file.path]) || NetworkMetadataProbe.cacheToken(connection, file) != generation) return@withContext
            _files.update { current -> current.map { if (it.path == file.path) it.copy(duration = newDuration) else it } }
            if (bitmap != null) {
              retainThumbnail(connection, file, bitmap, generation)
            } else {
              thumbnailVersions.remove(file.path)
              _thumbnails.update { it - file.path }
            }
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Error refreshing cache for ${file.name}", e)
        }
      }
      Log.i(TAG, "Refreshed cache for ${videos.size} videos in $folderPath")
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Error refreshing folder cache $folderPath", e)
    }
  }

  private suspend fun collectVideosRecursively(
    connection: NetworkConnection,
    path: String,
    depth: Int,
    out: MutableList<NetworkFile>,
  ) {
    if (depth > 10) return
    val files = repository.listFiles(connection, path).getOrNull() ?: return
    files.forEach { file ->
      if (file.isDirectory) {
        collectVideosRecursively(connection, file.path, depth + 1, out)
      } else if (file.mimeType?.startsWith("video/") == true) {
        out.add(file)
      }
    }
  }



  /**
   * Play a video file. The whole current directory (videos only, in list order)
   * is passed as the playback queue so playback continues to the next file
   * automatically; playback starts at the clicked video.
   */
  fun playVideo(file: NetworkFile) {
    val clickedAt = android.os.SystemClock.elapsedRealtime()
    viewModelScope.launch {
      val registeredStreams = mutableListOf<String>()
      var queueToken: String? = null
      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception(application.getString(R.string.network_connection_not_found))

        // Build the playback queue from the current directory listing
        val queue =
          _files.value.filter { !it.isDirectory && it.mimeType?.startsWith("video/") == true }
        val startIndex = queue.indexOfFirst { it.path == file.path }.takeIf { it >= 0 } ?: 0
        if (queue.isEmpty()) return@launch

        // Use proxy server for protocols that need seeking support
        val useProxy = connection.protocol in PROXY_PROTOCOLS
        val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance()

        // Register a proxy stream for every item so the player can switch to the
        // next file without re-registering. The player releases retired queues.
        val uris =
          withContext(Dispatchers.IO) { queue.map { queued ->
            if (useProxy) {
              val streamId = "${connectionId}_${System.nanoTime()}_${queued.path.hashCode()}"
              val proxyUrl =
                proxy.registerStream(
                  streamId = streamId,
                  connection = connection,
                  file = queued,
                )
              registeredStreams.add(streamId)
              android.net.Uri.parse(proxyUrl)
            } else {
              NetworkStreamingProvider.setConnection(connectionId, connection)
              NetworkStreamingProvider.getUri(application, connectionId, queued.path)
            }
          }

        }

        val startUri = uris[startIndex]

        // Launch the player
        val intent = Intent(Intent.ACTION_VIEW, startUri)
        intent.setClass(application, app.marlboroadvance.mpvex.ui.player.PlayerActivity::class.java)
        intent.putExtra("internal_launch", true)
        intent.putExtra("player_launch_elapsed_ms", clickedAt)
        intent.putExtra("launch_source", "network_stream")
        intent.putExtra("title", file.name)
        intent.putExtra("filename", file.name)
        // Pass the original network file path for stable media identifier (position saving)
        intent.putExtra("network_file_path", file.path)
        intent.putExtra("network_connection_id", connectionId)
        queueToken = app.marlboroadvance.mpvex.ui.player.PlaybackQueueRegistry.put(uris) {
          proxy.unregisterStreams(registeredStreams)
        }
        intent.putExtra(app.marlboroadvance.mpvex.ui.player.PlaybackQueueRegistry.EXTRA, queueToken)
        intent.putExtra("playlist_index", startIndex)
        intent.setDataAndType(startUri, file.mimeType ?: "video/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (!useProxy) {
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        Log.d("PlayerStartup", "queuePreparationMs=${android.os.SystemClock.elapsedRealtime() - clickedAt}, items=${uris.size}")
        application.startActivity(intent)
      } catch (e: Exception) {
        app.marlboroadvance.mpvex.ui.player.PlaybackQueueRegistry.discard(queueToken)
        app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
          .existingInstance()?.unregisterStreams(registeredStreams)
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.e(TAG, "Error playing video", e)
        _error.value = e.message ?: application.getString(R.string.network_unknown_error)
      }
    }
  }

  companion object {
    private const val TAG = "NetworkBrowserVM"

    // Protocols that require proxy server for seeking support
    private val PROXY_PROTOCOLS = setOf(
      NetworkProtocol.SMB,
      NetworkProtocol.FTP,
      NetworkProtocol.WEBDAV,
    )

    fun factory(
      application: Application,
      connectionId: Long,
      currentPath: String,
    ): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          NetworkBrowserViewModel(application, connectionId, currentPath)
        }
      }
  }
}
