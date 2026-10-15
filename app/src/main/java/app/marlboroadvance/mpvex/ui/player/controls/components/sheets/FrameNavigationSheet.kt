package app.marlboroadvance.mpvex.ui.player.controls.components.sheets

import app.marlboroadvance.mpvex.ui.player.LocalPlaybackControls
import app.marlboroadvance.mpvex.ui.player.PlaybackControls
import kotlinx.coroutines.CancellationException

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.presentation.components.PlayerSheet
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FrameNavigationSheet(
  currentFrame: Int,
  totalFrames: Int,
  onUpdateFrameInfo: () -> Unit,
  onPause: () -> Unit,
  onUnpause: () -> Unit,
  onPauseUnpause: () -> Unit,
  onSeekTo: (Int, Boolean) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val controls = LocalPlaybackControls.current
  val playback by controls.state.collectAsState()
  val generation = playback.generation
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var isSnapshotLoading by remember { mutableStateOf(false) }
  var isFrameStepping by remember { mutableStateOf(false) }
  val playerPreferences: PlayerPreferences = koinInject()
  val includeSubtitlesPrefState by playerPreferences.includeSubtitlesInSnapshot.collectAsState()
  var includeSubtitlesInSnapshot by remember { mutableStateOf(includeSubtitlesPrefState) }
  LaunchedEffect(includeSubtitlesPrefState) {
    includeSubtitlesInSnapshot = includeSubtitlesPrefState
  }

  // Use rememberUpdatedState for lambda parameters used in effects
  val currentOnPause by rememberUpdatedState(onPause)
  val currentOnUnpause by rememberUpdatedState(onUnpause)
  val currentOnUpdateFrameInfo by rememberUpdatedState(onUpdateFrameInfo)

  val isPaused = playback.paused
  val wasPausedInitially = remember(generation) { isPaused }
  val pos = playback.position.toInt()
  val dur = playback.duration?.toInt() ?: 0

  // Format timestamp based on current position
  val timestamp =
    remember(pos) {
      val hours = pos / 3600
      val minutes = (pos % 3600) / 60
      val seconds = pos % 60
      String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

  // Pause playback when the sheet opens
  LaunchedEffect(generation) {
    currentOnPause()
  }

  // Continuously update frame info as video plays
  LaunchedEffect(generation) {
    while (true) {
      currentOnUpdateFrameInfo()
      delay(100L)
    }
  }

  // Only resume playback when closing if it wasn't paused initially
  DisposableEffect(generation) {
    onDispose {
      if (!wasPausedInitially && controls.state.value.generation == generation) {
        currentOnUnpause()
      }
    }
  }

  PlayerSheet(onDismissRequest = onDismissRequest) {
    FrameNavigationCard(
      onPreviousFrame = {
        if (!isFrameStepping) {
          isFrameStepping = true
          coroutineScope.launch {
            try {
              if (controls.stepFrame(true, generation)) currentOnUpdateFrameInfo()
            } finally { isFrameStepping = false }
          }
        }
      },
      onNextFrame = {
        if (!isFrameStepping) {
          isFrameStepping = true
          coroutineScope.launch {
            try {
              if (controls.stepFrame(false, generation)) currentOnUpdateFrameInfo()
            } finally { isFrameStepping = false }
          }
        }
      },
      onPlayPause = {
        coroutineScope.launch {
          onPauseUnpause()
        }
      },
      isPaused = isPaused,
      onSnapshot = {
        coroutineScope.launch {
          isSnapshotLoading = true
          try {
            takeSnapshot(context, includeSubtitlesInSnapshot, controls, generation)
          } finally {
            isSnapshotLoading = false
          }
        }
      },
      isSnapshotLoading = isSnapshotLoading,
      isFrameStepping = isFrameStepping,
      currentFrame = currentFrame,
      totalFrames = totalFrames,
      timestamp = timestamp,
      duration = dur.toFloat(),
      pos = pos.toFloat(),
      onSeekTo = onSeekTo,
      title = {
        Column {
          FrameNavigationCardTitle(onClose = onDismissRequest)
          IncludeSubsToggle(
            includeSubs = includeSubtitlesInSnapshot,
            setIncludeSubs = { checked ->
              includeSubtitlesInSnapshot = checked
              coroutineScope.launch {
                playerPreferences.includeSubtitlesInSnapshot.set(checked)
              }
            },
          )
        }
      },
      modifier = modifier,
    )
  }
}

@Composable
private fun FrameNavigationCard(
  onPreviousFrame: () -> Unit,
  onNextFrame: () -> Unit,
  onPlayPause: () -> Unit,
  isPaused: Boolean,
  onSnapshot: () -> Unit,
  isSnapshotLoading: Boolean,
  isFrameStepping: Boolean,
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
  duration: Float,
  pos: Float,
  onSeekTo: (Int, Boolean) -> Unit,
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
) {
  val panelCardsColors: @Composable () -> CardColors = {
    val colors = CardDefaults.cardColors()
    colors.copy(
      containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
      disabledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
    )
  }

  val configuration = LocalConfiguration.current
  val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

  // Seekbar state management
  var userSliderPosition by remember { mutableFloatStateOf(0f) }
  var isSeeking by remember { mutableStateOf(false) }
  val videoProgress = if (duration > 0) pos / duration else 0f

  val seekbarProgress =
    if (isSeeking) {
      userSliderPosition
    } else {
      videoProgress
    }
  val animatedProgress by animateFloatAsState(
    targetValue = seekbarProgress,
    label = "seekbar",
  )

  Card(
    modifier =
      modifier
        .widthIn(max = 520.dp)
        .animateContentSize(),
    colors = panelCardsColors(),
  ) {
    Column(
      Modifier
        .verticalScroll(rememberScrollState())
        .padding(
          horizontal = MaterialTheme.spacing.medium,
          vertical = MaterialTheme.spacing.smaller,
        ),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      title()

      // Video seeking slider
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Slider(
          value = animatedProgress.coerceIn(0f, 1f),
          onValueChange = { newValue ->
            if (!isSeeking) isSeeking = true
            userSliderPosition = newValue.coerceIn(0f, 1f)
            // Optional live-seek for responsiveness
            val newPosition = (userSliderPosition * duration).toInt()
            onSeekTo(newPosition, false)
          },
          onValueChangeFinished = {
            // Commit final seek and return control to player updates
            val finalPosition = (userSliderPosition * duration).toInt()
            onSeekTo(finalPosition, true)
            isSeeking = false
          },
          modifier = Modifier.fillMaxWidth(),
        )
      }

      // Define button colors to make disabled buttons look the same as enabled
      val buttonColors =
        ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          disabledContainerColor = MaterialTheme.colorScheme.primary,
          disabledContentColor = MaterialTheme.colorScheme.onPrimary,
        )

      // Frame info, timestamp and navigation buttons
      if (isLandscape) {
        FrameNavigationLandscape(
          currentFrame = currentFrame,
          totalFrames = totalFrames,
          timestamp = timestamp,
          onPreviousFrame = onPreviousFrame,
          onPlayPause = onPlayPause,
          isPaused = isPaused,
          onNextFrame = onNextFrame,
          onSnapshot = onSnapshot,
          isSnapshotLoading = isSnapshotLoading,
          isFrameStepping = isFrameStepping,
          buttonColors = buttonColors,
        )
      } else {
        FrameNavigationPortrait(
          currentFrame = currentFrame,
          totalFrames = totalFrames,
          timestamp = timestamp,
          onPreviousFrame = onPreviousFrame,
          onPlayPause = onPlayPause,
          isPaused = isPaused,
          onNextFrame = onNextFrame,
          onSnapshot = onSnapshot,
          isSnapshotLoading = isSnapshotLoading,
          isFrameStepping = isFrameStepping,
          buttonColors = buttonColors,
        )
      }
    }
  }
}

@Composable
private fun FrameNavigationLandscape(
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
  onPreviousFrame: () -> Unit,
  onPlayPause: () -> Unit,
  isPaused: Boolean,
  onNextFrame: () -> Unit,
  onSnapshot: () -> Unit,
  isSnapshotLoading: Boolean,
  isFrameStepping: Boolean,
  buttonColors: androidx.compose.material3.ButtonColors,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Left side: Frame and timestamp info
    FrameInfoDisplay(currentFrame, totalFrames, timestamp)

    // Right side: Control buttons
    ControlButtons(
      onPreviousFrame = onPreviousFrame,
      onPlayPause = onPlayPause,
      isPaused = isPaused,
      onNextFrame = onNextFrame,
      onSnapshot = onSnapshot,
      isSnapshotLoading = isSnapshotLoading,
      isFrameStepping = isFrameStepping,
      buttonColors = buttonColors,
    )
  }
}

@Composable
private fun FrameNavigationPortrait(
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
  onPreviousFrame: () -> Unit,
  onPlayPause: () -> Unit,
  isPaused: Boolean,
  onNextFrame: () -> Unit,
  onSnapshot: () -> Unit,
  isSnapshotLoading: Boolean,
  isFrameStepping: Boolean,
  buttonColors: androidx.compose.material3.ButtonColors,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    // Frame and timestamp info
    FrameInfoDisplay(currentFrame, totalFrames, timestamp)

    // Control buttons
    ControlButtons(
      onPreviousFrame = onPreviousFrame,
      onPlayPause = onPlayPause,
      isPaused = isPaused,
      onNextFrame = onNextFrame,
      onSnapshot = onSnapshot,
      isSnapshotLoading = isSnapshotLoading,
      isFrameStepping = isFrameStepping,
      buttonColors = buttonColors,
    )
  }
}

@Composable
private fun FrameInfoDisplay(
  currentFrame: Int,
  totalFrames: Int,
  timestamp: String,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.frame_colon),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.tertiary,
      )
      Text(
        text =
          if (totalFrames > 0) {
            "$currentFrame / $totalFrames"
          } else {
            "$currentFrame"
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.timestamp_colon),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.tertiary,
      )
      Text(
        text = timestamp,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun ControlButtons(
  onPreviousFrame: () -> Unit,
  onPlayPause: () -> Unit,
  isPaused: Boolean,
  onNextFrame: () -> Unit,
  onSnapshot: () -> Unit,
  isSnapshotLoading: Boolean,
  isFrameStepping: Boolean,
  buttonColors: androidx.compose.material3.ButtonColors,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Button(
      onClick = onPreviousFrame,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isFrameStepping,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      Icon(
        Icons.Default.FastRewind,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
      )
    }

    Button(
      onClick = onPlayPause,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isFrameStepping,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      Icon(
        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
      )
    }

    Button(
      onClick = onNextFrame,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isFrameStepping,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      Icon(
        Icons.Default.FastForward,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
      )
    }

    Button(
      onClick = onSnapshot,
      modifier = Modifier.size(56.dp),
      enabled = !isSnapshotLoading && !isFrameStepping,
      colors = buttonColors,
      contentPadding = PaddingValues(0.dp),
    ) {
      if (isSnapshotLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(32.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        Icon(
          Icons.Default.CameraAlt,
          contentDescription = null,
          modifier = Modifier.size(32.dp),
        )
      }
    }
  }
}

@Composable
private fun FrameNavigationCardTitle(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      stringResource(R.string.player_sheets_frame_navigation_title),
      style = MaterialTheme.typography.headlineMedium,
    )
    IconButton(onClose) {
      Icon(
        Icons.Default.Close,
        null,
        modifier = Modifier.size(32.dp),
      )
    }
  }
}

@Composable
private fun IncludeSubsToggle(
  includeSubs: Boolean,
  setIncludeSubs: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(bottom = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Start,
  ) {
    Switch(
      checked = includeSubs,
      onCheckedChange = setIncludeSubs,
    )
    Text(
      text = stringResource(R.string.player_sheets_frame_navigation_include_subtitles),
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(start = MaterialTheme.spacing.smaller),
    )
  }
}

private suspend fun takeSnapshot(
  context: Context,
  includeSubtitles: Boolean,
  controls: PlaybackControls,
  generation: Long,
) = withContext(Dispatchers.IO) {
  var tempFile: File? = null
  var pendingImage: android.net.Uri? = null
  var pendingLegacy: File? = null
  val resolver = context.contentResolver
  try {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
    val file = File.createTempFile("mpv_snapshot_", ".png", context.cacheDir)
    tempFile = file
    val filename = "mpv_snapshot_${timestamp}_${file.name.removePrefix("mpv_snapshot_")}"
    if (!controls.captureFrame(file.absolutePath, includeSubtitles, generation)) return@withContext
    check(file.length() > 0L) { context.getString(R.string.snapshot_create_failed) }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/mpvSnaps")
        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
      }
      val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
        context.getString(R.string.snapshot_media_store_entry_failed)
      }
      pendingImage = uri
      checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      values.clear()
      values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
      check(resolver.update(uri, values, null, null) > 0)
      pendingImage = null
    } else {
      val directory = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "mpvSnaps")
      check(directory.isDirectory || directory.mkdirs()) { context.getString(R.string.snapshot_directory_create_failed) }
      val destination = File(directory, filename)
      pendingLegacy = destination
      file.copyTo(destination)
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      android.media.MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf("image/png"), null)
      pendingLegacy = null
    }
    withContext(Dispatchers.Main) {
      Toast.makeText(context, context.getString(R.string.player_sheets_frame_navigation_snapshot_saved), Toast.LENGTH_SHORT).show()
    }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    withContext(Dispatchers.Main) {
      Toast.makeText(context, context.getString(R.string.snapshot_create_failed), Toast.LENGTH_LONG).show()
    }
  } finally {
    pendingImage?.let { runCatching { resolver.delete(it, null, null) } }
    pendingLegacy?.delete()
    tempFile?.delete()
  }
}
