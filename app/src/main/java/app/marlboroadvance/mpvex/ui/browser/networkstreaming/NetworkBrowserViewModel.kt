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
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.domain.network.NetworkProtocol
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.NetworkSortType
import app.marlboroadvance.mpvex.preferences.SortOrder
import app.marlboroadvance.mpvex.repository.NetworkRepository
import app.marlboroadvance.mpvex.utils.sort.SortUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
  private val runningThumbnails = ConcurrentHashMap.newKeySet<String>()

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
    viewModelScope.launch {
      _isLoading.value = true
      _error.value = null

      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        repository.listFiles(connection, currentPath)
          .onSuccess { fileList ->
            // Pre-load cached durations so the list displays instantly without re-probing
            val cachedDurations = NetworkMetadataProbe.getCachedDurations(connection, fileList)
            _files.value = fileList.map { file ->
              cachedDurations[file.path]?.takeIf { it > 0 }?.let { duration ->
                file.copy(duration = duration)
              } ?: file
            }
            probeMissingDurations(connection, fileList)
          }
          .onFailure { e ->
            _error.value = e.message ?: "Unknown error"
          }
      } catch (e: Exception) {
        _error.value = e.message ?: "Unknown error"
      } finally {
        _isLoading.value = false
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
    currentFiles.forEach { file ->
      ensureThumbnail(file)
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
    val videos = fileList.filter {
      !it.isDirectory && it.mimeType?.startsWith("video/") == true && it.duration <= 0
    }
    if (videos.isEmpty()) return

    viewModelScope.launch(Dispatchers.IO) {
      coroutineScope {
        videos.map { file ->
          async {
            try {
              val duration = NetworkMetadataProbe.probeDuration(connection, file)
              if (duration > 0) {
                withContext(Dispatchers.Main) {
                  _files.update { current ->
                    if (current.any { it.path == file.path && it.duration != duration }) {
                      current.map { if (it.path == file.path) it.copy(duration = duration) else it }
                    } else {
                      current
                    }
                  }
                }
              }
            } catch (e: Exception) {
              Log.w(TAG, "Error probing duration for ${file.name}", e)
            }
          }
        }.awaitAll()
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
  fun ensureThumbnail(file: NetworkFile) {
    if (file.isDirectory) return
    if (!appearancePreferences.showNetworkThumbnails.get()) return
    if (_thumbnails.value.containsKey(file.path)) return
    if (thumbnailJobs.containsKey(file.path)) return

    val job =
      viewModelScope.launch {
        try {
          val connection = repository.getConnectionById(connectionId)
            ?: return@launch
          runningThumbnails.add(file.path)
          try {
            // probeThumbnail serves memory + disk cache immediately, so cached
            // files are near-instant regardless of the fast-path check.
            val bitmap = NetworkMetadataProbe.probeThumbnail(connection, file)
            if (bitmap != null) {
              _thumbnails.value = _thumbnails.value + (file.path to bitmap)
            }
          } finally {
            runningThumbnails.remove(file.path)
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Error probing thumbnail for ${file.name}", e)
        } finally {
          // Safe: jobs are de-duplicated per path, so the entry we inserted is
          // always the one that should be removed.
          thumbnailJobs.remove(file.path)
        }
      }
    thumbnailJobs[file.path] = job
  }

  /**
   * Cancel queued (not yet decoding) thumbnail jobs whose file is outside the
   * current visible+prefetch window. In-flight decodes are left alone so we
   * never waste an already-downloaded range and re-fetch it on scroll-back.
   */
  fun cancelThumbnailsExcept(keepPaths: Set<String>) {
    thumbnailJobs.keys.forEach { path ->
      if (path !in keepPaths && path !in runningThumbnails) {
        thumbnailJobs.remove(path)?.cancel()
      }
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
      val duration = NetworkMetadataProbe.probeDuration(connection, file)
      withContext(Dispatchers.Main) {
        _files.value = _files.value.map {
          if (it.path == file.path) it.copy(duration = duration) else it
        }
      }
      if (appearancePreferences.showNetworkThumbnails.get()) {
        val bitmap = NetworkMetadataProbe.probeThumbnail(connection, file)
        if (bitmap != null) {
          withContext(Dispatchers.Main) {
            _thumbnails.value = _thumbnails.value + (file.path to bitmap)
          }
        }
      }
      Log.i(TAG, "Refreshed cache for ${file.name} (${duration}ms)")
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

      coroutineScope {
        videos.map { file ->
          async {
            try {
              NetworkMetadataProbe.invalidateCache(connection, file)
              val newDuration = NetworkMetadataProbe.probeDuration(connection, file)
              val bitmap =
                if (showThumbnails) {
                  NetworkMetadataProbe.probeThumbnail(connection, file)
                } else {
                  null
                }
              Triple(file.path, newDuration, bitmap)
            } catch (e: Exception) {
              Log.w(TAG, "Error refreshing cache for ${file.name}", e)
              Triple(file.path, 0L, null)
            }
          }
        }.awaitAll().forEach { (path, newDuration, bitmap) ->
          withContext(Dispatchers.Main) {
            if (newDuration > 0) {
              _files.value = _files.value.map {
                if (it.path == path) it.copy(duration = newDuration) else it
              }
            }
            if (bitmap != null) {
              _thumbnails.value = _thumbnails.value + (path to bitmap)
            }
          }
        }
      }
      Log.i(TAG, "Refreshed cache for ${videos.size} videos in $folderPath")
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
    viewModelScope.launch {
      try {
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        // Build the playback queue from the current directory listing
        val queue =
          _files.value.filter { !it.isDirectory && it.mimeType?.startsWith("video/") == true }
        val startIndex = queue.indexOfFirst { it.path == file.path }.takeIf { it >= 0 } ?: 0
        if (queue.isEmpty()) return@launch

        // Use proxy server for protocols that need seeking support
        val useProxy = connection.protocol in PROXY_PROTOCOLS
        val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance()

        // Register a proxy stream for every item so the player can switch to the
        // next file without re-registering (streams live until app shutdown).
        val uris =
          queue.map { queued ->
            if (useProxy) {
              val streamId = "${connectionId}_${System.nanoTime()}_${queued.path.hashCode()}"
              val proxyUrl =
                proxy.registerStream(
                  streamId = streamId,
                  connection = connection,
                  filePath = queued.path,
                  fileSize = queued.size,
                  mimeType = queued.mimeType ?: "video/mp4",
                )
              android.net.Uri.parse(proxyUrl)
            } else {
              NetworkStreamingProvider.setConnection(connectionId, connection)
              NetworkStreamingProvider.getUri(application, connectionId, queued.path)
            }
          }

        val startUri = uris[startIndex]

        // Launch the player
        val intent = Intent(Intent.ACTION_VIEW, startUri)
        intent.setClass(application, app.marlboroadvance.mpvex.ui.player.PlayerActivity::class.java)
        intent.putExtra("internal_launch", true)
        intent.putExtra("launch_source", "network_stream")
        intent.putExtra("title", file.name)
        intent.putExtra("filename", file.name)
        // Pass the original network file path for stable media identifier (position saving)
        intent.putExtra("network_file_path", file.path)
        intent.putExtra("network_connection_id", connectionId)
        intent.putParcelableArrayListExtra("playlist", ArrayList(uris))
        intent.putExtra("playlist_index", startIndex)
        intent.setDataAndType(startUri, file.mimeType ?: "video/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (!useProxy) {
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        application.startActivity(intent)
      } catch (e: Exception) {
        Log.e(TAG, "Error playing video", e)
        _error.value = e.message ?: "Unknown error"
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
