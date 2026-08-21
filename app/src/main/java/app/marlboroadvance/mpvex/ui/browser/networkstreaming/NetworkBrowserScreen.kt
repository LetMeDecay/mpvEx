package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.database.dao.NetworkConnectionDao
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.preferences.AppearancePreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.NetworkSortType
import app.marlboroadvance.mpvex.preferences.SortOrder
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.presentation.components.pullrefresh.PullRefreshBox
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkFolderCard
import app.marlboroadvance.mpvex.ui.browser.cards.NetworkVideoCard
import app.marlboroadvance.mpvex.ui.browser.components.BrowserTopBar
import app.marlboroadvance.mpvex.ui.browser.dialogs.SortDialog
import app.marlboroadvance.mpvex.ui.browser.dialogs.VisibilityToggle
import app.marlboroadvance.mpvex.ui.browser.states.EmptyState
import app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.serialization.Serializable
import my.nanihadesuka.compose.LazyColumnScrollbar
import my.nanihadesuka.compose.ScrollbarSettings
import org.koin.compose.koinInject

// How many items beyond the viewport to prefetch thumbnails for while scrolling.
private const val THUMBNAIL_PREFETCH_AHEAD = 10

@Serializable
data class NetworkBrowserScreen(
  val connectionId: Long,
  val connectionName: String,
  val currentPath: String = "/",
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current

    val viewModel: NetworkBrowserViewModel =
      viewModel(
        key = "NetworkBrowser_${connectionId}_$currentPath",
        factory =
          NetworkBrowserViewModel.factory(
            context.applicationContext as android.app.Application,
            connectionId,
            currentPath,
          ),
      )

    val files by viewModel.files.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()

    // UI State
    val isRefreshing = remember { mutableStateOf(false) }
    var sortDialogOpen by rememberSaveable { mutableStateOf(false) }
    // Saveable so the scroll position is restored when returning from a sub-directory.
    // navigation3 keeps rememberSaveable state per NavEntry via SaveableStateHolder.
    val listState = rememberLazyListState()

    // Preferences
    val browserPreferences = koinInject<BrowserPreferences>()
    val appearancePreferences = koinInject<AppearancePreferences>()
    val networkSortType by viewModel.sortType.collectAsState()
    val networkSortOrder by viewModel.sortOrder.collectAsState()
    val showSizeChip by browserPreferences.showSizeChip.collectAsState()
    val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
    val showNetworkThumbnails by appearancePreferences.showNetworkThumbnails.collectAsState()

    // Load files when connectionId or currentPath changes
    LaunchedEffect(connectionId, currentPath) {
      viewModel.loadFiles()
    }

    // Selection state (long-press to enter selection mode)
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isInSelectionMode = selectedPaths.isNotEmpty()
    val selectedItems = files.filter { selectedPaths.contains(it.path) }

    fun toggleSelection(item: NetworkFile) {
      selectedPaths =
        if (selectedPaths.contains(item.path)) {
          selectedPaths - item.path
        } else {
          selectedPaths + item.path
        }
    }

    fun clearSelection() {
      selectedPaths = emptySet()
    }

    BackHandler(enabled = isInSelectionMode) {
      clearSelection()
    }

    BackHandler(enabled = !isInSelectionMode) {
      backstack.removeLastOrNull()
    }

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = connectionName,
          isInSelectionMode = isInSelectionMode,
          selectedCount = selectedPaths.size,
          totalCount = files.size,
          onBackClick = { backstack.removeLastOrNull() },
          onCancelSelection = { clearSelection() },
          onSortClick = { sortDialogOpen = true },
          onSearchClick = null,
          onSettingsClick = {
            backstack.add(app.marlboroadvance.mpvex.ui.preferences.PreferencesScreen)
          },
          onDeleteClick = null,
          onRenameClick = null,
          isSingleSelection = false,
          onInfoClick = null,
          onShareClick = null,
          onPlayClick = null,
          onSelectAll = null,
          onInvertSelection = null,
          onDeselectAll = null,
          onRefreshClick = {
            selectedItems.forEach { item ->
              viewModel.refreshCache(item)
            }
            clearSelection()
          },
        )
      },
    ) { padding ->
      NetworkBrowserContent(
        files = files,
        connectionId = connectionId,
        connectionName = connectionName,
        isLoading = isLoading && files.isEmpty(),
        isRefreshing = isRefreshing,
        error = error,
        thumbnails = thumbnails,
        listState = listState,
        selectedPaths = selectedPaths,
        onRefresh = { viewModel.loadFiles() },
        onFolderClick = { folder ->
          if (isInSelectionMode) {
            toggleSelection(folder)
          } else {
            backstack.add(
              NetworkBrowserScreen(
                connectionId = connectionId,
                connectionName = connectionName,
                currentPath = folder.path,
              ),
            )
          }
        },
        onVideoClick = { video ->
          if (isInSelectionMode) {
            toggleSelection(video)
          } else {
            viewModel.playVideo(video)
          }
        },
        onVideoVisible = { video ->
          viewModel.ensureThumbnail(
            video,
            NetworkMetadataProbe.ThumbnailPriority.VISIBLE,
          )
        },
        onVideoPrefetch = { video ->
          viewModel.ensureThumbnail(
            video,
            NetworkMetadataProbe.ThumbnailPriority.PREFETCH,
          )
        },
        onCancelPrefetch = { keepPaths ->
          viewModel.cancelThumbnailsExcept(keepPaths)
        },
        onItemLongClick = { item ->
          toggleSelection(item)
        },
        modifier = Modifier.padding(padding),
      )
    }

    // Sort & View Options dialog
    val sortOrderLabels =
      mapOf(
        NetworkSortType.Title.displayName to
          Pair(stringResource(R.string.network_sort_az), stringResource(R.string.network_sort_za)),
        NetworkSortType.Date.displayName to
          Pair(stringResource(R.string.network_sort_oldest), stringResource(R.string.network_sort_newest)),
        NetworkSortType.Size.displayName to
          Pair(stringResource(R.string.network_sort_smallest), stringResource(R.string.network_sort_largest)),
        NetworkSortType.Duration.displayName to
          Pair(stringResource(R.string.network_sort_shortest), stringResource(R.string.network_sort_longest)),
      )
    val defaultSortOrderLabels =
      Pair(stringResource(R.string.network_sort_asc), stringResource(R.string.network_sort_desc))
    SortDialog(
      isOpen = sortDialogOpen,
      onDismiss = { sortDialogOpen = false },
      title = stringResource(R.string.network_sort_view_options),
      sortType = networkSortType.displayName,
      onSortTypeChange = { typeName ->
        NetworkSortType.entries
          .find { it.displayName == typeName }
          ?.let { viewModel.setSortType(it) }
      },
      sortOrderAsc = networkSortOrder.isAscending,
      onSortOrderChange = { isAsc ->
        viewModel.setSortOrder(if (isAsc) SortOrder.Ascending else SortOrder.Descending)
      },
      types = listOf(
        NetworkSortType.Title.displayName,
        NetworkSortType.Date.displayName,
        NetworkSortType.Size.displayName,
        NetworkSortType.Duration.displayName,
      ),
      icons = listOf(
        Icons.Filled.Title,
        Icons.Filled.CalendarToday,
        Icons.Filled.SwapVert,
        Icons.Filled.Timer,
      ),
      getLabelForType = { type, _ ->
        sortOrderLabels[type] ?: defaultSortOrderLabels
      },
      visibilityToggles = listOf(
        VisibilityToggle(
          label = stringResource(R.string.network_visibility_full_name),
          checked = unlimitedNameLines,
          onCheckedChange = { appearancePreferences.unlimitedNameLines.set(it) },
        ),
        VisibilityToggle(
          label = stringResource(R.string.network_visibility_size),
          checked = showSizeChip,
          onCheckedChange = { browserPreferences.showSizeChip.set(it) },
        ),
        VisibilityToggle(
          label = stringResource(R.string.network_visibility_thumbnails),
          checked = showNetworkThumbnails,
          onCheckedChange = {
            appearancePreferences.showNetworkThumbnails.set(it)
            if (it) {
              viewModel.reloadWithThumbnails()
            }
          },
        ),
      ),
    )
  }
}

@Composable
private fun NetworkBrowserContent(
  files: List<NetworkFile>,
  connectionId: Long,
  connectionName: String,
  isLoading: Boolean,
  isRefreshing: MutableState<Boolean>,
  error: String?,
  thumbnails: Map<String, Bitmap>,
  listState: LazyListState,
  selectedPaths: Set<String>,
  onRefresh: suspend () -> Unit,
  onFolderClick: (NetworkFile) -> Unit,
  onVideoClick: (NetworkFile) -> Unit,
  onVideoVisible: (NetworkFile) -> Unit,
  onVideoPrefetch: (NetworkFile) -> Unit,
  onCancelPrefetch: (Set<String>) -> Unit,
  onItemLongClick: (NetworkFile) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Load connection details
  val dao = org.koin.compose.koinInject<NetworkConnectionDao>()
  var connection by remember { mutableStateOf<NetworkConnection?>(null) }

  LaunchedEffect(connectionId) {
    connection = dao.getConnectionById(connectionId)
  }

  when {
    isLoading -> {
      Box(
        modifier = modifier
          .fillMaxSize()
          .padding(bottom = 80.dp), // Account for bottom navigation bar
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    error != null -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.Filled.Folder,
          title = stringResource(R.string.network_error_loading_files),
          message = error,
        )
      }
    }

    files.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.Filled.Folder,
          title = stringResource(R.string.network_empty_folder),
          message = stringResource(R.string.network_empty_folder_message),
        )
      }
    }

    else -> {
      val folders = files.filter { it.isDirectory }
      val videos = files.filter { !it.isDirectory && it.mimeType?.startsWith("video/") == true }
      val networkListState = listState

      // Keep window of paths we still care about: visible items plus the
      // prefetch-ahead distance. When it shrinks during fast scrolling, cancel
      // queued thumbnail jobs that have scrolled far out of view.
      val keepWindow by remember(files) {
        derivedStateOf {
          val layout = networkListState.layoutInfo
          val total = layout.totalItemsCount
          val videoStart = (total - videos.size).coerceAtLeast(0)
          val firstVis = networkListState.firstVisibleItemIndex
          val lastVis = layout.visibleItemsInfo.lastOrNull()?.index ?: firstVis
          val lastIndex = (videos.size - 1).coerceAtLeast(0)
          val firstVideo = (firstVis - videoStart).coerceIn(0, lastIndex)
          val lastVideo = (lastVis - videoStart).coerceIn(0, lastIndex)
          buildSet {
            for (i in (firstVideo - THUMBNAIL_PREFETCH_AHEAD)..(lastVideo + THUMBNAIL_PREFETCH_AHEAD)) {
              videos.getOrNull(i)?.path?.let(::add)
            }
          }
        }
      }
      LaunchedEffect(keepWindow) {
        onCancelPrefetch(keepWindow)
      }

      // Check if at top of list to hide scrollbar during pull-to-refresh
      val isAtTop by remember {
        derivedStateOf {
          networkListState.firstVisibleItemIndex == 0 && networkListState.firstVisibleItemScrollOffset == 0
        }
      }

      // Only show scrollbar if list has more than 20 items (folders + videos)
      val hasEnoughItems = (folders.size + videos.size) > 20

      // Animate scrollbar alpha
      val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isAtTop || !hasEnoughItems) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
        label = "scrollbarAlpha",
      )

      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        listState = networkListState,
        modifier = modifier.fillMaxSize(),
      ) {
        val navigationBarHeight = app.marlboroadvance.mpvex.ui.browser.LocalNavigationBarHeight.current
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(bottom = navigationBarHeight)
        ) {
          LazyColumnScrollbar(
            state = networkListState,
            settings = ScrollbarSettings(
              thumbUnselectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f * scrollbarAlpha),
              thumbSelectedColor = MaterialTheme.colorScheme.primary.copy(alpha = scrollbarAlpha),
            ),
          ) {
            LazyColumn(
              state = networkListState,
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 8.dp,
                bottom = navigationBarHeight
              ),
            ) {
            // Folders section
            if (folders.isNotEmpty()) {
              item {
                Text(
                  text = stringResource(R.string.network_section_folders),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                )
              }
              items(
                items = folders,
                key = { it.path },
              ) { folder ->
                NetworkFolderCard(
                  file = folder,
                  onClick = { onFolderClick(folder) },
                  onLongClick = { onItemLongClick(folder) },
                  isSelected = selectedPaths.contains(folder.path),
                  modifier = Modifier,
                )
              }
            }

            // Videos section
            if (videos.isNotEmpty()) {
              item {
                Text(
                  text = stringResource(R.string.network_section_videos),
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                )
              }
              itemsIndexed(
                items = videos,
                key = { _, video -> video.path },
              ) { index, video ->
                // Trigger lazy thumbnail loading when the item becomes visible,
                // prefetching a few items ahead so thumbnails are ready as the
                // user scrolls. ensureThumbnail is idempotent (memory + disk cache),
                // and actual network work stays capped by NetworkMetadataProbe.
                LaunchedEffect(video.path) {
                  onVideoVisible(video)
                  for (offset in 1..THUMBNAIL_PREFETCH_AHEAD) {
                    videos.getOrNull(index + offset)?.let(onVideoPrefetch)
                  }
                }
                // Only show card if connection is loaded
                connection?.let { conn ->
                  NetworkVideoCard(
                    file = video,
                    connection = conn,
                    onClick = { onVideoClick(video) },
                    onLongClick = { onItemLongClick(video) },
                    isSelected = selectedPaths.contains(video.path),
                    thumbnail = thumbnails[video.path],
                    modifier = Modifier,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
}
