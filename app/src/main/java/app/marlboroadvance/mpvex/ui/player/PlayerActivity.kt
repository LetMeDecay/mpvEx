package app.marlboroadvance.mpvex.ui.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.databinding.PlayerLayoutBinding
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.controls.PlayerControls
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps
import app.marlboroadvance.mpvex.utils.media.HttpUtils
import app.marlboroadvance.mpvex.utils.media.NetworkMediaIdentity
import app.marlboroadvance.mpvex.utils.media.InFlightPrefetchTracker
import app.marlboroadvance.mpvex.utils.media.PlaybackPositionPolicy
import app.marlboroadvance.mpvex.utils.media.PlaylistPrefetchOrder
import app.marlboroadvance.mpvex.utils.media.SubtitleOps
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import app.marlboroadvance.mpvex.utils.storage.FileFilterUtils
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

/**
 * Main player activity that handles video playback using the MPV library.
 *
 * This activity manages:
 * - Video playback using MPV library
 * - System UI visibility (immersive mode)
 * - Audio focus management
 * - Picture-in-Picture (PiP) mode
 * - Background playback service
 * - MediaSession for external controls (Android Auto, Bluetooth, etc.)
 * - Playback state persistence and restoration
 * - Subtitle and audio track management
 * - Hardware key event handling
 *
 * @see PlayerViewModel for UI state management
 * @see MediaPlaybackService for background playback functionality
 */
@Suppress("TooManyFunctions", "LargeClass")
class PlayerActivity :
  AppCompatActivity(),
  PlayerHost {
  // ==================== ViewModels and Bindings ====================

  /**
   * View model for managing player UI state.
   */
  internal val viewModel: PlayerViewModel by viewModels<PlayerViewModel> {
    PlayerViewModelProviderFactory(this)
  }

  /**
   * Binding for the player layout.
   */
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }

  /**
   * Observer for MPV events.
   */
  private val playerObserver by lazy { PlayerObserver(this) }

  // ==================== Dependency Injection ====================

  /**
   * Repository for managing playback state.
   */
  private val playbackStateRepository: PlaybackStateRepository by inject()

  /**
   * Repository for managing playlists.
   */
  private val playlistRepository: app.marlboroadvance.mpvex.database.repository.PlaylistRepository by inject()

  /**
   * Preferences for player settings.
   */
  private val playerPreferences: PlayerPreferences by inject()

  /**
   * Preferences for audio settings.
   */
  private val audioPreferences: AudioPreferences by inject()

  /**
   * Preferences for subtitle settings.
   */
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  /**
   * Preferences for advanced settings.
   */
  private val advancedPreferences: AdvancedPreferences by inject()

  /**
   * Preferences for browser settings.
   */
  private val browserPreferences: BrowserPreferences by inject()

  /**
   * Manager for file operations.
   */

  /**
   * Track selector for automatic audio/subtitle selection
   */
  private val trackSelector: TrackSelector by lazy {
    TrackSelector(audioPreferences, subtitlesPreferences)
  }

  // ==================== Views ====================

  /**
   * The MPV player view.
   */
  val player by lazy { binding.player }

  // ==================== State Management ====================

  /**
   * Current video file name being played.
   */
  private var fileName = ""

  /**
   * Unique identifier for the current media, used for saving/loading playback state.
   * For network streams, this includes a hash of the URI to ensure uniqueness.
   */
  @Volatile private var mediaIdentifier = ""

  /**
   * Playlist of URIs for sequential playback
   */
  internal var playlist: List<Uri> = emptyList()
    set(value) {
      field = value
      playbackStreams.replaceQueue(value.mapNotNull(::playbackProxyStreamId).toSet())
    }

  /**
   * Current index in the playlist
   */
  internal var playlistIndex: Int = 0

  /**
   * Shuffled order of playlist indices (when shuffle is enabled)
   */
  private var shuffledIndices: List<Int> = emptyList()

  /**
   * Current position in shuffled playlist (when shuffle is enabled)
   */
  private var shuffledPosition: Int = 0

  /**
   * Playlist ID for tracking play history (optional, only for custom playlists)
   */
  private var playlistId: Int? = null

  /**
   * Tracks the starting offset of the loaded playlist window in the full playlist.
   * Used for windowed loading to prevent ANR with large playlists.
   */
  private var playlistWindowOffset: Int = 0

  /**
   * Total count of items in the full playlist (when using windowed loading).
   * -1 means unknown or not using windowed loading.
   */
  var playlistTotalCount: Int = -1
    private set

  /**
   * Indicates whether the current playlist is an M3U playlist sourced from database.
   * Used to skip thumbnail/metadata extraction for network streams.
   */
  private var isM3uPlaylist: Boolean = false

  /**
   * Helper for managing Picture-in-Picture mode.
   */
  private lateinit var pipHelper: MPVPipHelper

  private var isReady = false // Single flag: true when video loaded and ready
  private var isUserFinishing = false
  private var isManualBackgroundPlayback = false // Track manual background playback trigger
  private var noisyReceiverRegistered = false
  private var mpvInitialized = false // Track MPV initialization state
  private var savePlaybackStateJob: kotlinx.coroutines.Job? = null // Track ongoing save job
  @Volatile private var playbackStateLoadGeneration = 0L
  private var initialIntentExtrasApplied = false
  private var mediaPreparationJob: Job? = null
  private var playlistLoadJob: Job? = null
  private var nextPrefetchJob: Job? = null // Only one next-item prefetch watcher may be active
  // Only tracks requests currently in flight. HeaderCache has a bounded LRU,
  // so a successful prefetch must not suppress a later request forever.
  private val inFlightPrefetchKeys = InFlightPrefetchTracker()
  private var wasPlayingBeforePause = false // Track if video was playing before pause

  // ==================== Background Playback ====================

  /**
   * Reference to the background playback service.
   */
  private var mediaPlaybackService: MediaPlaybackService? = null

  /**
   * Tracks whether we're currently bound to the background playback service.
   */
  private var serviceBound = false

  // ==================== MediaSession ====================

  /**
   * MediaSession for integration with system media controls, Android Auto, and Wear OS.
   */
  private lateinit var mediaSession: MediaSession

  /**
   * Tracks whether MediaSession has been successfully initialized.
   */
  private var mediaSessionInitialized = false

  /**
   * Builder for MediaSession playback states.
   */
  private lateinit var playbackStateBuilder: PlaybackState.Builder

  // ==================== Audio Focus ====================

  /**
   * Audio focus request for API 26+.
   */
  private var audioFocusRequest: AudioFocusRequest? = null

  /**
   * Callback to restore audio focus after it's been lost and regained.
   */
  private var restoreAudioFocus: () -> Unit = {}

  // ==================== Broadcast Receivers ====================

  /**
   * Receiver for handling noisy audio events.
   */
  private val noisyReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
          viewModel.pause()
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      }
    }

  /**
   * Listener for audio focus changes.
   */
  private val audioFocusChangeListener =
    AudioManager.OnAudioFocusChangeListener { focusChange ->
      when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
          -> {
          // Save current state to restore later
          val oldRestore = restoreAudioFocus
          val wasPlayerPaused = viewModel.paused ?: false
          viewModel.pause()
          restoreAudioFocus = {
            oldRestore()
            if (!wasPlayerPaused) viewModel.unpause()
          }
        }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          // Lower volume temporarily
          MpvGateway.command("multiply", "volume", "0.5")
          restoreAudioFocus = {
            MpvGateway.command("multiply", "volume", "2")
          }
        }

        AudioManager.AUDIOFOCUS_GAIN -> {
          // Restore previous audio state
          restoreAudioFocus()
          restoreAudioFocus = {}
        }

        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
          Log.d(TAG, "Audio focus request failed")
        }
      }
    }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onCreate(savedInstanceState: Bundle?) {
    val createdAt = android.os.SystemClock.elapsedRealtime()
    val clickedAt = intent.getLongExtra("player_launch_elapsed_ms", -1L)
    if (clickedAt in 0..createdAt) Log.d("PlayerStartup", "clickToActivityMs=${createdAt - clickedAt}")
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContentView(binding.root)

    // OPTIMIZATION: Set volume control stream so hardware buttons control media volume
    volumeControlStream = AudioManager.STREAM_MUSIC

    setupBackPressHandler()
    binding.controls.setContent {
      MpvexTheme {
        androidx.compose.foundation.layout.Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = androidx.compose.ui.Alignment.Center,
        ) { androidx.compose.material3.CircularProgressIndicator() }
      }
    }
    lifecycleScope.launch {
      val prepareStarted = android.os.SystemClock.elapsedRealtime()
      var prepared: ConfigSnapshotStore.Lease? = null
      try {
        prepared = PlayerConfigRepository.prepare(applicationContext, PlayerConfigRepository.Request(
          advancedPreferences.mpvConfStorageUri.get(), advancedPreferences.mpvConf.get(),
          advancedPreferences.inputConf.get(), subtitlesPreferences.fontsFolder.get(),
        ))
        Log.d("PlayerStartup", "configPreparationMs=${android.os.SystemClock.elapsedRealtime() - prepareStarted}")
        firstPlayerDraw.await()
        lifecycle.withStarted {
          if (!isFinishing) {
            setupMPV(prepared!!)
            prepared = null // Native engine now owns the snapshot lease.
            completePlayerSetup()
            setupStartedPlayer()
            updateVolume()
          }
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        Log.e(TAG, "Unable to prepare player configuration")
        android.widget.Toast.makeText(this@PlayerActivity,
          getString(app.marlboroadvance.mpvex.R.string.player_config_failed), android.widget.Toast.LENGTH_LONG).show()
        finish()
      } finally {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { prepared?.close() }
      }
    }
    binding.root.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
      private var reported = false
      override fun onDraw() {
        if (reported) return
        reported = true
        Log.d("PlayerStartup", "activityToFirstDrawMs=${android.os.SystemClock.elapsedRealtime() - createdAt}")
        binding.root.post {
          binding.root.viewTreeObserver.removeOnDrawListener(this)
          firstPlayerDraw.complete(Unit)
        }
      }
    })
  }

  private fun completePlayerSetup() {
    MediaPlaybackService.createNotificationChannel(this)
    setupAudio()
    setupPlayerControls()
    setupPipHelper()
    setupMediaSession()

    playlistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
    playlistIndex = intent.getIntExtra("playlist_index", 0)

    intent.data?.let(::playbackProxyStreamId)?.let(playbackStreams::remember)

    // Load playlist from intent extras first (fast path - backward compatibility)
    playlist = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    val launchIntent = intent
    val initialItem = playlist.getOrNull(playlistIndex)
    parsePathFromIntent(launchIntent)?.let { path ->
      viewModel.beginFileChange(path)
      preparePlayback(
        path,
        readName = { initialItem?.let(::getFileNameFromUri) ?: getFileName(launchIntent) },
        identify = { name -> initialItem?.let { getMediaIdentifierFromUri(it, name) }
          ?: getMediaIdentifier(launchIntent, name) },
        configure = { setHttpHeadersFromExtras(launchIntent.extras) },
      )
    }

    // Only set orientation immediately if NOT in Video mode
    // For Video mode, wait for video-params/aspect to become available
    if (playerPreferences.orientation.get() != PlayerOrientation.Video) {
      setOrientation()
    }

    // Apply persisted shuffle state after playlist is loaded
    viewModel.applyPersistedShuffleState()

    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
  }

  override fun attachBaseContext(newBase: Context?) {
    super.attachBaseContext(newBase)
  }

  private fun setupBackPressHandler() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        @RequiresApi(Build.VERSION_CODES.P)
        override fun handleOnBackPressed() {
          handleBackPress()
        }
      },
    )
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun handleBackPress() {
    if (!mpvInitialized) { finish(); return }
    // Dismiss overlays first
    if (viewModel.sheetShown.value != Sheets.None) {
      viewModel.sheetShown.update { Sheets.None }
      viewModel.showControls()
      return
    }

    if (viewModel.panelShown.value != Panels.None) {
      viewModel.panelShown.update { Panels.None }
      viewModel.showControls()
      return
    }

    // Check if auto PIP is enabled - enter PIP mode instead of finishing
    if (playerPreferences.autoPiPOnNavigation.get() && isReady) {
      pipHelper.enterPipMode()
      return
    }

    isUserFinishing = true
    finish()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupPlayerControls() {
    binding.controls.setContent {
      MpvexTheme {
        PlayerControls(
          viewModel = viewModel,
          onBackPress = {
            isUserFinishing = true
            finish()
          },
          modifier = Modifier,
        )
      }
    }
  }

  /**
   * Initializes the Picture-in-Picture helper.
   */
  internal fun stopFromSystem() {
    isManualBackgroundPlayback = false
    isUserFinishing = true
    finish()
  }

  private fun setupPipHelper() {
    pipHelper = MPVPipHelper(activity = this, mpvView = player, controls = viewModel.controls)
  }

  private fun setupAudio() {
    audioPreferences.audioChannels.get().let {
      runCatching {
        MpvGateway.setPropertyString(it.property, it.value)
      }.onFailure { e ->
        Log.e(TAG, "Error setting audio channels: ${it.property}=${it.value}", e)
      }
    }

    if (!serviceBound) {
      audioFocusRequest =
        AudioFocusRequest
          .Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(
            AudioAttributes
              .Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
              .build(),
          ).setOnAudioFocusChangeListener(audioFocusChangeListener)
          .setAcceptsDelayedFocusGain(true)
          .setWillPauseWhenDucked(true)
          .build()
      requestAudioFocus()
    }
  }

  /**
   * @return true if audio focus was granted immediately, false otherwise
   */
  override fun requestAudioFocus(): Boolean {
    val req = audioFocusRequest ?: return false
    val result = audioManager.requestAudioFocus(req)
    return when (result) {
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
        restoreAudioFocus = {}
        true
      }

      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
        restoreAudioFocus = { requestAudioFocus() }
        false
      }

      else -> {
        restoreAudioFocus = {}
        false
      }
    }
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    // Enter PIP mode when user presses home button if auto PIP is enabled
    if (playerPreferences.autoPiPOnNavigation.get() && isReady && !isFinishing) {
      pipHelper.enterPipMode()
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onDestroy() {
    Log.d(TAG, "PlayerActivity onDestroy")
    if (!mpvInitialized) { super.onDestroy(); return }

    runCatching {
      // OPTIMIZATION: Prevent any further UI updates or callbacks
      isReady = false
      nextPrefetchJob?.cancel()
      playlistLoadJob?.cancel()
      mediaPreparationJob?.cancel()
      nextPrefetchJob = null

      // Only stop the service if we're not doing manual background playback
      if ((isUserFinishing || isFinishing) && !isManualBackgroundPlayback) {
        if (serviceBound) {
          runCatching { unbindService(serviceConnection) }
          serviceBound = false
        }
        stopService(Intent(this, MediaPlaybackService::class.java))
        mediaPlaybackService = null
      }

      // Wait for any pending save operation to complete before destroying MPV
      // The IO job persists a captured snapshot and never needs the UI thread.
      savePlaybackStateJob?.let { job ->
        Log.d(TAG, "Waiting for save playback state job to complete...")
        runCatching {
          // The save owns its IO lifetime; lifecycle cancellation cannot discard it.
          kotlinx.coroutines.runBlocking {
            job.join()
          }
        }
        Log.d(TAG, "Save playback state job completed")
      }

      cleanupMPV()
      cleanupAudio()
      cleanupReceivers()
      releaseMediaSession()
    }.onFailure { e ->
      Log.e(TAG, "Error during onDestroy", e)
    }

    if (isFinishing && !isManualBackgroundPlayback && !mpvInitialized) {
      app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
        .existingInstance()?.setPlaybackUrl(null)
      app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
        .existingInstance()?.unregisterStreams(playbackStreams.releaseAll())
    }
    super.onDestroy()
  }

  private fun cleanupMPV() {
    if (!mpvInitialized) return

    player.isExiting = true

    // Stop media notification service when activity is destroyed
    endBackgroundPlayback()

    // Don't cleanup MPV if we're doing manual background playback
    if (!isFinishing || isManualBackgroundPlayback) return

    runCatching {
      MpvGateway.removeObserver(playerObserver)

      viewModel.stopPlayback()
      // Worker and stop execute only on IO and never await the UI/event thread.
      // Do not destroy the singleton while a synchronous load is still in flight.
      kotlinx.coroutines.runBlocking { viewModel.awaitPlaybackStopped() }
      player.releaseEngineSurface()
      MpvGateway.destroy()
      mpvInitialized = false
      val retiredConfig = configLease
      configLease = null
      kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { retiredConfig?.close() }
    }.onFailure { e ->
      Log.e(TAG, "Error cleaning up MPV", e)
    }
  }

  override fun abandonAudioFocus() {
    if (restoreAudioFocus != {}) {
      audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
      restoreAudioFocus = {}
    }
  }

  private fun cleanupAudio() {
    abandonAudioFocus()
  }

  private fun cleanupReceivers() {
    if (noisyReceiverRegistered) {
      runCatching {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onPause() {
    if (!mpvInitialized) { super.onPause(); return }
    runCatching {
      val isInPip = isInPictureInPictureMode
      val shouldPause = (!audioPreferences.automaticBackgroundPlayback.get() && !isManualBackgroundPlayback) || 
                        (isUserFinishing && !isManualBackgroundPlayback)

      // Capture native state before retiring the session or sending stop.
      saveVideoPlaybackState(fileName)
      if (isFinishing && !isManualBackgroundPlayback) {
        player.isExiting = true
        viewModel.stopPlayback()
      } else if (!isInPip && shouldPause) {
        wasPlayingBeforePause = !(viewModel.paused ?: true)
        viewModel.pause()
      }

      // Restore UI immediately when user is finishing for instant feedback
      if (isUserFinishing && !isInPip && !isManualBackgroundPlayback) {
        restoreSystemUI()
      }

    }.onFailure { e ->
      Log.e(TAG, "Error during onPause", e)
    }

    super.onPause()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun finish() {
    if (!mpvInitialized) { super.finish(); return }
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finish", e)
    }

    super.finish()
  }

  // finishAndRemoveTask() was added in API 21, but since our minSdk is 26, it's always available
  override fun finishAndRemoveTask() {
    if (!mpvInitialized) { super.finishAndRemoveTask(); return }
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      isUserFinishing = true
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finishAndRemoveTask", e)
    }

    super.finishAndRemoveTask()
  }

  override fun onStop() {
    if (!mpvInitialized) { super.onStop(); return }
    runCatching {
      pipHelper.onStop()
      saveVideoPlaybackState(fileName)

      if (noisyReceiverRegistered) {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }

      // Handle background playback based on preferences
      val shouldAllowBackgroundPlayback = isManualBackgroundPlayback || 
                                          audioPreferences.automaticBackgroundPlayback.get()
      
      // Pause playback if background playback is not enabled and user is finishing
      if (!shouldAllowBackgroundPlayback && (isUserFinishing || isFinishing)) {
        viewModel.pause()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onStop", e)
    }

    super.onStop()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onStart() {
    super.onStart()
    if (mpvInitialized) setupStartedPlayer()
  }

  private fun setupStartedPlayer() {
    runCatching {
      setupWindowFlags()
      setupSystemUI()

      if (!noisyReceiverRegistered) {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
        noisyReceiverRegistered = true
      }

      if (playerPreferences.rememberBrightness.get()) {
        val brightness = playerPreferences.defaultBrightness.get()
        if (brightness != BRIGHTNESS_NOT_SET) {
          viewModel.changeBrightnessTo(brightness)
        }
      }
      
      // Reset manual background playback flag when returning to foreground
      isManualBackgroundPlayback = false
    }.onFailure { e ->
      Log.e(TAG, "Error during onStart", e)
    }
  }

  private fun setupWindowFlags() {
    pipHelper.updatePictureInPictureParams()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setFlags(
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupSystemUI() {
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

    // Set status bar color for when it will be shown (with controls)
    if (playerPreferences.showSystemStatusBar.get()) {
      window.statusBarColor = android.graphics.Color.parseColor("#80000000") // Semi-transparent black
    }

    // Always start with status bar hidden - it will show when controls are shown
    try {
      windowInsetsController.apply {
        hide(WindowInsetsCompat.Type.statusBars())
        hide(WindowInsetsCompat.Type.navigationBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to setup system UI insets", e)
    }

    // Don't use LOW_PROFILE if we plan to show status bar with controls
    // LOW_PROFILE causes only icons to show without background
    @Suppress("DEPRECATION")
    binding.root.systemUiVisibility =
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        if (playerPreferences.showSystemStatusBar.get()) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun restoreSystemUI() {
    // Clear flags first for immediate effect
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Set cutout mode before showing bars for smoother transition
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT

    // Update window insets configuration
    WindowCompat.setDecorFitsSystemWindows(window, true)

    // Restore default behavior and show bars in one go
    try {
      windowInsetsController.apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to restore system UI insets", e)
    }
  }

  /**
   * Initializes the MPV player with the necessary paths and observers.
   */
  private val firstPlayerDraw = kotlinx.coroutines.CompletableDeferred<Unit>()
  private var configLease: ConfigSnapshotStore.Lease? = null

  private fun setupMPV(snapshot: ConfigSnapshotStore.Lease) {
    configLease = snapshot
    if (snapshot.usedFallback) android.widget.Toast.makeText(this,
      getString(app.marlboroadvance.mpvex.R.string.player_config_fallback), android.widget.Toast.LENGTH_LONG).show()
    player.configDirectory = snapshot.directory
    // NOW initialize MPV - it will find and load the scripts we just copied
    val initializeStarted = android.os.SystemClock.elapsedRealtime()
    MpvGateway.open()
    player.initialize(snapshot.directory.path, cacheDir.path)
    Log.d("PlayerStartup", "mpvInitializationMs=${android.os.SystemClock.elapsedRealtime() - initializeStarted}")
    mpvInitialized = true
    Log.d(TAG, "MPV initialized")

    // Add observer after initialization
    MpvGateway.addObserver(playerObserver)
  }

  override fun onResume() {
    super.onResume()
    if (mpvInitialized) updateVolume()
  }

  /**
   * Updates the volume level to match the system volume.
   *
   * This method updates the current volume level by getting the current system volume
   * and adjusting the MPV volume accordingly. It ensures that the MPV volume is set
   * to the maximum allowed value if the system volume is lower than the maximum.
   */
  private fun updateVolume() {
    viewModel.currentVolume.update {
      audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).also { volume ->
        if (volume < viewModel.maxVolume) {
          viewModel.changeMPVVolumeTo(MAX_MPV_VOLUME)
        }
      }
    }
  }

  /**
   * Processes intent extras to set initial playback position, subtitles, and HTTP headers.
   *
   * This method checks the intent extras for the following keys:
   * - "position": The initial playback position in seconds.
   * - "subs": A list of subtitle URIs to add.
   * - "subs.enable": A list of subtitle URIs to enable.
   * - "headers": A list of HTTP headers to set for network playback.
   *
   * @param extras Bundle containing intent extras
   */
  private fun setIntentExtras(extras: Bundle?) {
    if (extras == null) return

    getIntentPositionSeconds(extras)?.let {
      MpvGateway.setPropertyInt("time-pos", it)
    }

    addSubtitlesFromExtras(extras)
    setHttpHeadersFromExtras(extras)
  }

  private fun getIntentPositionSeconds(extras: Bundle?): Int? =
    extras?.getInt("position", POSITION_NOT_SET)
      ?.takeIf { it != POSITION_NOT_SET }
      ?.div(MILLISECONDS_TO_SECONDS)

  /**
   * Adds subtitle tracks from intent extras.
   *
   * This method checks the intent extras for the "subs" key, which contains a list
   * of subtitle URIs to add. It also checks for the "subs.enable" key, which contains
   * a list of subtitle URIs to enable.
   *
   * @param extras Bundle containing subtitle URIs
   */
  private fun addSubtitlesFromExtras(extras: Bundle) {
    if (!extras.containsKey("subs")) return

    val subList = Utils.getParcelableArray<Uri>(extras, "subs")
    val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

    lifecycleScope.launch(Dispatchers.Default) {
      for (suburi in subList) {
        val subfile = suburi.resolveUri(this@PlayerActivity) ?: continue
        val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

        Log.v(TAG, "Adding subtitles from intent extras: $subfile")
        MpvGateway.command("sub-add", subfile, flag)
      }
    }
  }

  /**
   * Sets HTTP headers from intent extras for network playback.
   *
   * This method checks the intent extras for the "headers" key, which contains a list
   * of HTTP headers to set. It sets the User-Agent header and any additional headers
   * specified in the list.
   *
   * Also automatically adds Referer header based on the URL origin if not already provided.
   *
   * @param extras Bundle containing HTTP headers
   */
  private fun setHttpHeadersFromExtras(extras: Bundle?) {
    // Build header map starting with auto-detected referer
    val headerMap = mutableMapOf<String, String>()

    // Automatically extract and set referer domain from the URL
    val uri = extractUriFromIntent(intent)
    if (uri != null && HttpUtils.isNetworkStream(uri)) {
      HttpUtils.extractRefererDomain(uri)?.let { referer ->
        headerMap["Referer"] = referer
        Log.d(TAG, "Auto-detected Referer: $referer")
      }
    }

    // Process headers from extras (these can override the auto-detected referer)
    extras?.getStringArray("headers")?.let { headers ->
      if (headers.isEmpty()) return@let

      if (headers[0].startsWith("User-Agent", ignoreCase = true)) {
        MpvGateway.setPropertyString("user-agent", headers[1])
      }

      if (headers.size > 2) {
        headers
          .asSequence()
          .drop(2)
          .chunked(2)
          .filter { it.size == 2 }
          .forEach { (key, value) ->
            headerMap[key] = value
          }
      }
    }

    // Set all headers in MPV
    if (headerMap.isNotEmpty()) {
      val headersString = headerMap
        .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
        .joinToString(",")

      MpvGateway.setPropertyString("http-header-fields", headersString)
      Log.d(TAG, "Set HTTP headers: $headersString")
    }
  }

  /**
   * Sets HTTP headers for a specific URI (used for playlist items).
   * Automatically extracts and sets the Referer header based on the URI origin.
   *
   * @param uri The URI to extract referer from and set headers for
   */
  private fun setHttpHeadersForUri(uri: Uri) {
    if (!HttpUtils.isNetworkStream(uri)) return

    val headerMap = mutableMapOf<String, String>()

    // Automatically extract and set referer domain from the URI
    HttpUtils.extractRefererDomain(uri)?.let { referer ->
      headerMap["Referer"] = referer
      Log.d(TAG, "Auto-detected Referer for playlist item: $referer")
    }

    // Set all headers in MPV
    if (headerMap.isNotEmpty()) {
      val headersString = headerMap
        .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
        .joinToString(",")

      MpvGateway.setPropertyString("http-header-fields", headersString)
      Log.d(TAG, "Set HTTP headers for playlist item: $headersString")
    }
  }

  /**
   * Parses the file path from the intent.
   *
   * This method checks the intent action and data to determine the file path.
   * It supports the following actions:
   * - ACTION_VIEW: The file path is contained in the intent data.
   * - ACTION_SEND: The file path is contained in the intent extras.
   *
   * @param intent The intent containing the file URI
   * @return A source address without opening the content provider
   */
  private fun parsePathFromIntent(intent: Intent): String? =
    when (intent.action) {
      Intent.ACTION_VIEW -> intent.data?.toString()
      Intent.ACTION_SEND -> parsePathFromSendIntent(intent)
      else -> intent.getStringExtra("uri")
    }

  /**
   * Parses the file path from a SEND intent.
   *
   * This method checks the intent extras for the file path.
   *
   * @param intent The SEND intent
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromSendIntent(intent: Intent): String? =
    if (intent.hasExtra(Intent.EXTRA_STREAM)) {
      val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
      }
      uri?.toString()
    } else {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        val uri = text.trim().toUri()
        if (uri.isHierarchical && !uri.isRelative) {
          uri.toString()
        } else {
          null
        }
      }
    }

  /**
   * Extracts and resolves the file name from the intent.
   *
   * @param intent The intent containing the file URI
   * @return The display name of the file, or empty string if not found
   */
  private fun getFileName(intent: Intent): String {
    // First check if a custom title/filename was provided via intent extras
    intent.getStringExtra("title")?.let { return it }
    intent.getStringExtra("filename")?.let { return it }

    val uri = extractUriFromIntent(intent) ?: return ""

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Extracts filename from URI, handling URL encoding and network URLs properly.
   * For network streams, returns a temporary name that will be updated async via HTTP headers.
   *
   * @param uri The URI to extract filename from
   * @return The extracted filename
   */
  private fun extractFileNameFromUri(uri: Uri): String {
    // For HTTP/HTTPS URLs, extract from path (will be updated async via HTTP headers)
    if (HttpUtils.isNetworkStream(uri)) {
      // Get the last path segment and decode URL encoding
      val path = uri.path ?: return uri.host ?: "Network Stream"
      val lastSegment = path.substringAfterLast("/")

      if (lastSegment.isNotBlank()) {
        // Decode URL encoding (e.g., %20 -> space)
        return try {
          java.net.URLDecoder.decode(lastSegment, "UTF-8")
            .substringBefore("?") // Remove query parameters
            .substringBefore("#") // Remove fragments (only for network streams)
            .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
        } catch (e: Exception) {
          lastSegment
            .substringBefore("?")
            .substringBefore("#")
        }
      }

      // If no filename in path, use hostname
      return uri.host ?: "Network Stream"
    }

    // For file:// and content:// URIs - preserve # characters as they're part of the filename
    val lastSegment = uri.lastPathSegment?.substringAfterLast("/") ?: uri.path ?: "Unknown Video"
    
    // For local files, only decode URL encoding but preserve # characters
    return try {
      java.net.URLDecoder.decode(lastSegment, "UTF-8")
    } catch (e: Exception) {
      lastSegment
    }
  }

  /**
   * Gets the display title for a playlist item URI.
   *
   * @param uri The URI to get the title for
   * @return The display name/title of the file
   */
  internal fun getPlaylistItemTitle(uri: Uri): String {
    // Local proxy URLs carry a stream id in the path, not the real filename.
    resolveProxyFileName(uri)?.let { return it }

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Plays a playlist item by index.
   *
   * @param index The index of the playlist item to play
   */
  internal fun playPlaylistItem(index: Int) {
    if (index in playlist.indices) {
      loadPlaylistItem(index)
    }
  }

  /**
   * Extracts the URI from the intent based on intent type.
   *
   * @param intent The intent to extract URI from
   * @return The extracted URI, or null if not found
   */
  private fun extractUriFromIntent(intent: Intent): Uri? =
    if (intent.type == "text/plain") {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
    } else {
      intent.data ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
      }
    }

  /**
   * Queries the content resolver to get the display name for a URI.
   *
   * @param uri The URI to query
   * @return The display name, or null if not found
   */
  private fun getDisplayNameFromUri(uri: Uri): String? {
    if (uri.scheme != "content") return null
    return runCatching {
      contentResolver
        .query(
          uri,
          arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.onFailure { e ->
      Log.e(TAG, "Error getting display name from URI", e)
    }.getOrNull()
  }

  /**
   * Handles device configuration changes.
   *
   * @param newConfig The new configuration
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    if (isReady) {
      handleConfigurationChange()
    }
  }

  /**
   * Handles configuration changes by updating video aspect ratio.
   */
  private fun handleConfigurationChange() {
    if (!isInPictureInPictureMode) {
      // Configuration changes don't affect aspect ratio
    } else {
      viewModel.hideControls()
    }
  }

  // ==================== MPV Event Observers ====================

  /**
   * Observer callback for MPV property changes (Long values).
   * Handles video width and height changes.
   *
   * @param property The property name that changed
   * @param value The new Long value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: Long,
  ) {
    when (property) {
      "video-params/w",
      "video-params/h" -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return

        val aspect = player.getVideoOutAspect()
        Log.d(TAG, "Video dimension changed: $property, aspect: $aspect")
        pipHelper.updatePictureInPictureParams()
        // Update orientation when video dimensions change (fixes Video orientation mode)
        if (playerPreferences.orientation.get() == PlayerOrientation.Video && aspect != null) {
          setOrientation()
        }

        // Re-apply Anime4K shaders (check for resolution limit)
        player.applyAnime4KShaders()
      }
    }
  }

  /**
   * Observer callback for MPV property changes (Boolean values).
   * Handles pause state and end-of-file events.
   *
   * @param property The property name that changed
   * @param value The new Boolean value
   */
  internal fun onObserverEvent(
    property: String,
    value: Boolean,
    eventGeneration: Long,
    eventPath: String?,
  ) {
    when (property) {
      "pause" -> {
        handlePauseStateChange(value)
        // Ensure isReady is set when playback starts
        if (!value && !isReady && viewModel.playbackSession.loaded) {
          isReady = true
        }
      }
      "eof-reached" -> {
        if (viewModel.playbackSession.loaded &&
          viewModel.playbackSession.acceptsEvent(eventPath, eventGeneration) &&
          MpvGateway.getPropertyBoolean("eof-reached") == value) handleEndOfFile(value)
      }
    }
  }

  /**
   * Handles pause state changes by managing screen-on flag and MediaSession state.
   *
   * @param isPaused true if playback is paused, false if playing
   */
  private fun handlePauseStateChange(isPaused: Boolean) {
    if (isPaused) {
      // Only clear keep-screen-on if the preference is NOT enabled
      if (!playerPreferences.keepScreenOnWhenPaused.get()) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    } else {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    updateMediaSessionPlaybackState(!isPaused)
    runCatching {
      if (isInPictureInPictureMode) {
        pipHelper.updatePictureInPictureParams()
      }
    }.onFailure { /* Silently ignore PiP update failures */ }
  }

  /**
   * Handles end-of-file event by playing next in playlist if available, otherwise finishing activity if configured.
   *
   * @param isEof true if end of file reached
   */
  private fun handleEndOfFile(isEof: Boolean) {
    if (isEof) {
      // Check if we should repeat the current file
      if (viewModel.shouldRepeatCurrentFile()) {
        MpvGateway.command("seek", "0", "absolute")
        viewModel.unpause()
        return
      }

      // Handle playlist playback
      if (playlist.isNotEmpty()) {
        val hasNextItem = if (viewModel.shuffleEnabled.value) {
          shuffledPosition < shuffledIndices.size - 1
        } else {
          playlistIndex < playlist.size - 1
        }

        // Check if autoplay next video is enabled
        val autoplayEnabled = playerPreferences.autoplayNextVideo.get()

        if (hasNextItem && (autoplayEnabled || viewModel.shouldRepeatPlaylist())) {
          // Play next item in playlist
          playNext()
          return
        } else if (viewModel.shouldRepeatPlaylist()) {
          // At end of playlist with repeat ALL: restart from beginning
          if (viewModel.shuffleEnabled.value) {
            // Regenerate shuffle order and start from beginning
            generateShuffledIndices()
            shuffledPosition = 0
            playlistIndex = shuffledIndices[0]
            loadPlaylistItem(playlistIndex)
          } else {
            // Normal mode: restart from index 0
            playlistIndex = 0
            loadPlaylistItem(0)
          }
          return
        } else if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
          // No autoplay or no next item, end of playlist: close if setting is enabled
          finishAndRemoveTask()
        }
        // If autoplay is off and closeAfterReachingEndOfVideo is off, just stay on current video
      } else {
        // Single video playback (no playlist)
        if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
          finishAndRemoveTask()
        }
      }
    }
    // Reaching a retained endpoint releases metadata work. Replays and seeks
    // restore the gate when mpv reports eof=false; automatic transitions return above.
    app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
      .existingInstance()?.setPlaybackEnded(MpvGateway.getPropertyString("path"), isEof)
  }

  /**
   * Observer callback for MPV property changes (MPVNode values).
   *
   * This method is called when an MPV property (with MPVNode value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new MPVNode value
   */
  internal fun onObserverEvent(
    property: String,
    value: MPVNode,
  ) {
    // Currently no MPVNode properties are handled
  }

  /**
   * Observer callback for MPV property changes (Double values).
   *
   * This method is called when an MPV property (with Double value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new Double value
   */
  internal fun onObserverEvent(
    property: String,
    value: Double,
  ) {
    // Handle Double properties
    when (property) {
      "video-params/aspect" -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return

        val aspect = player.getVideoOutAspect()
        Log.d(TAG, "video-params/aspect changed: $aspect")
        pipHelper.updatePictureInPictureParams()
        // Update orientation when video aspect ratio changes (fixes Video orientation mode)
        // BUT: Don't update if aspect is being overridden (stretch/custom aspect mode)
        // to prevent infinite orientation switching loop
        val aspectOverride = MpvGateway.getPropertyDouble("video-aspect-override") ?: -1.0
        if (playerPreferences.orientation.get() == PlayerOrientation.Video && 
            aspect != null && 
            aspectOverride <= 0.0) {
          setOrientation()
        }
      }
    }
  }

  /**
   * Observer callback for MPV property changes (String values).
   *
   * This method is called when an MPV property (with String value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new String value
   */
  internal fun onObserverEvent(
    property: String,
    value: String,
  ) {
    // Currently no String properties are handled
  }

  /**
   * Observer callback for MPV property changes (no value parameter).
   * Handles properties with no value parameter.
   *
   * @param property The property name that changed
   */
  internal fun onObserverEvent(property: String) {
    // Currently no properties use this signature
  }

  /**
   * Handles MPV core events such as file loaded and playback restart.
   *
   * Called by the player when critical playback events occur.
   *
   * @param eventId The MPV event ID
   */
  internal fun event(eventId: Int, eventPath: String?, eventGeneration: Long) {
    if (!viewModel.playbackSession.acceptsEvent(eventPath, eventGeneration)) return
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
        if (!viewModel.playbackSession.confirmLoaded(
            eventPath, eventGeneration, MpvGateway.getPropertyString("path"),
          )) return
        isReady = true
        handleFileLoaded()
      }

      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
        // Playback can already be running after a missed/rejected FILE_LOADED.
        // Confirm the current file before opening the timeline gate, and run
        // load initialization only once (ordinary seeks also emit RESTART).
        if (!viewModel.playbackSession.acceptsPath(MpvGateway.getPropertyString("path"))) return
        if (viewModel.playbackSession.confirmLoaded(
            eventPath, eventGeneration, MpvGateway.getPropertyString("path"),
          )) {
          isReady = true
          handleFileLoaded()
        }
        if (!viewModel.playbackSession.loaded) return
        if (MpvGateway.getPropertyBoolean("eof-reached") == false) {
          app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
            .existingInstance()?.setPlaybackEnded(eventPath, false)
        }
        player.isExiting = false
        if (!isReady) {
          isReady = true
        }
      }
    }
  }

  internal fun onPlaybackFailure(generation: Long) {
    if (viewModel.playbackSession.generation != generation) return
    isReady = false
    viewModel.nativeFileFailed(generation)
  }

  /**
   * Handles the file loaded event from MPV.
   * Initializes playback state, loads saved playback data, restores custom settings,
   * applies user preferences, and sets up metadata and media session.
   */
  private fun handleFileLoaded() {
    viewModel.nativeFileReady()
    loadDeferredPlaylist()
    // Consult mpv, not playlistIndex: an older FILE_LOADED callback can arrive
    // while a newer intent has already replaced the queue.
    MpvGateway.getPropertyString("path")?.takeIf { it.isNotBlank() }?.let { path ->
      val current = playbackProxyStreamId(Uri.parse(path))
      app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
        .existingInstance()?.setPlaybackUrl(path)
      app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
        .existingInstance()?.unregisterStreams(playbackStreams.releaseUnused(current))
    }
    // Name and history identity were resolved once, on IO, before this load.
    viewModel.refreshPlaylistItems()

    val loadedMediaIdentifier = mediaIdentifier
    val loadGeneration = ++playbackStateLoadGeneration
    val restoreTicket = viewModel.playbackSession.ticket()
    val isInitialFileLoad = !initialIntentExtrasApplied
    val intentPosition = if (isInitialFileLoad) getIntentPositionSeconds(intent.extras) else null

    // Start media notification service (like YouTube - always show notification)
    startBackgroundPlayback()

    // Reset AB loop values when video changes
    viewModel.clearABLoop()

    // Position/subtitle extras belong to the initial launch only. Reapplying a
    // stale position on every FILE_LOADED event makes a playlist item inherit
    // the launch item's position.
    if (!initialIntentExtrasApplied) {
      setIntentExtras(intent.extras)
      initialIntentExtrasApplied = true
    }

    // Clear MPV's previous position before an asynchronous state lookup. Keep
    // an explicitly supplied initial launch position visible until the lookup
    // confirms whether a saved state should override it.
    PlaybackPositionPolicy.positionBeforeStateLookup(
      isInitialLoad = isInitialFileLoad,
      intentPosition = intentPosition,
    )?.let { MpvGateway.setPropertyInt("time-pos", it) }

    lifecycleScope.launch(Dispatchers.IO) {
      // Load playback state (will skip track restoration if preferred language configured)
      // A recent switch may still be persisting this file's previous snapshot.
      savePlaybackStateJob?.join()
      val state = loadVideoPlaybackState(loadedMediaIdentifier)
      if (!isCurrentPlaybackStateLoad(loadGeneration, loadedMediaIdentifier)) return@launch
      val hasState = state != null

      // Apply state only if this FILE_LOADED event is still current. The
      // database lookup is asynchronous and can otherwise restore an older
      // item's state after the user has already switched files.
      withContext(Dispatchers.Main) {
        if (isCurrentPlaybackStateLoad(loadGeneration, loadedMediaIdentifier)) {
          applyPlaybackState(state, viewModel.playbackSession.isCurrent(restoreTicket))
          applyDefaultSettings(state)
        }
      }
      if (!isCurrentPlaybackStateLoad(loadGeneration, loadedMediaIdentifier)) return@launch

      // Apply track selection logic (defaults only apply when no saved state)
      trackSelector.onFileLoaded(hasState)
      if (!isCurrentPlaybackStateLoad(loadGeneration, loadedMediaIdentifier)) return@launch

      // Apply default zoom only if there's no saved state
      if (!hasState) {
        withContext(Dispatchers.Main) {
          if (!isCurrentPlaybackStateLoad(loadGeneration, loadedMediaIdentifier)) return@withContext
          val zoomPreference = playerPreferences.defaultVideoZoom.get()
          MpvGateway.setPropertyDouble("video-zoom", zoomPreference.toDouble())
          viewModel.setVideoZoom(zoomPreference)
        }
      }

      // Apply saved aspect ratio setting
      withContext(Dispatchers.Main) {
        if (!isCurrentPlaybackStateLoad(loadGeneration, loadedMediaIdentifier)) return@withContext
        val savedAspect = playerPreferences.defaultVideoAspect.get()
        val savedCustomRatio = playerPreferences.defaultCustomAspectRatio.get()
        
        if (savedCustomRatio > 0) {
          // Apply custom aspect ratio
          viewModel.setCustomAspectRatio(savedCustomRatio)
        } else {
          // Apply standard aspect mode (Fit, Crop, or Stretch)
          viewModel.changeVideoAspect(savedAspect, showUpdate = false)
        }
      }
    }

    // This is the reliable lifecycle point for both the initial Surface-ready load
    // call and later playlist loadfile calls.
    startNextPrefetch()

    // Save to recently played when video actually loads and plays
    lifecycleScope.launch(Dispatchers.IO) {
      if (playlist.isNotEmpty()) {
        // For playlist items, save using the current URI
        // All items are loaded, so playlistIndex is the direct index
        if (playlistIndex >= 0 && playlistIndex < playlist.size) {
          saveRecentlyPlayedForUri(playlist[playlistIndex], fileName)
        } else {
          Log.w(TAG, "Cannot save recently played: invalid playlist index $playlistIndex (playlist size: ${playlist.size})")
        }
      } else {
        // For non-playlist videos, use the original saveRecentlyPlayed
        saveRecentlyPlayed()
      }
    }

    // Only set orientation immediately if NOT in Video mode
    // For Video mode, wait for video-params/aspect to become available
    if (playerPreferences.orientation.get() != PlayerOrientation.Video) {
      setOrientation()
    } else {
      // For Video mode, try to set orientation after a short delay to ensure
      // video dimensions are available
      lifecycleScope.launch {
        kotlinx.coroutines.delay(100)
        if (mpvInitialized && !player.isExiting && !isFinishing) {
          val aspect = player.getVideoOutAspect()
          Log.d(TAG, "handleFileLoaded - Video mode, aspect after delay: $aspect")
          if (aspect != null && aspect > 0) {
            setOrientation()
          }
        }
      }
    }

    applySubtitlePreferences()

    // Don't force media-title for m3u/m3u8 streams - let MPV provide it
    if (!isCurrentStreamM3U()) {
      MpvGateway.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    viewModel.unpause()

    if (subtitlesPreferences.autoloadMatchingSubtitles.get()) {
      lifecycleScope.launch {
        // Resolve the URI that MPV is currently loading. For playlist playback
        // this is different from the original launch URI after the first item.
        // Proxy URLs are ephemeral, so use their registry entry for the actual
        // remote path and connection id.
        val currentUri = playlist.getOrNull(playlistIndex) ?: extractUriFromIntent(intent)
        val currentStreamInfo = currentUri?.let(::resolveProxyStreamInfo)
        val networkFilePath = currentStreamInfo?.filePath
          ?: intent.getStringExtra("network_file_path")
        val networkConnectionId = currentStreamInfo?.connection?.id
          ?: intent.getLongExtra("network_connection_id", -1L)

        if (networkFilePath != null && networkConnectionId != -1L) {
          // Pass network file path and connection ID for subtitle discovery
          SubtitleOps.autoloadSubtitles(
            videoFilePath = networkFilePath,
            videoFileName = fileName,
            networkConnectionId = networkConnectionId,
          )
        } else {
          // Regular file or direct network stream
          val filePath = MpvGateway.getPropertyString("path")
          if (filePath != null) {
            SubtitleOps.autoloadSubtitles(
              videoFilePath = filePath,
              videoFileName = fileName,
            )
          }
        }
      }
    }

    updateMediaSessionMetadata(
      title = fileName,
      durationMs = (MpvGateway.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L,
    )
    updateMediaSessionPlaybackState(isPlaying = true)

    // Asynchronously fetch better filename from HTTP headers for network streams
    fetchNetworkStreamTitle()
  }

  /**
   * Fetches a better title from HTTP headers for network streams asynchronously.
   * Updates the title in UI, MPV, and media session if a better name is found.
   */
  private fun fetchNetworkStreamTitle() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val uri = extractUriFromIntent(intent)
        if (uri == null || !HttpUtils.isNetworkStream(uri)) {
          return@launch
        }

        // Skip fetching for m3u/m3u8 streams - let MPV provide the title
        if (isCurrentStreamM3U()) {
          Log.d(TAG, "Skipping title fetch for m3u/m3u8 stream: $uri")
          return@launch
        }

        // Skip fetching if title was provided in intent extras (e.g. from Jellyfin or other external launchers)
        // This prevents overwriting the correct title with a generic filename from the URL (like "stream")
        if (intent.hasExtra("title") || intent.hasExtra("filename")) {
          Log.d(TAG, "Skipping title fetch because title was explicitly provided in intent: $fileName")
          return@launch
        }

        // Skip fetching for local proxy URLs (SMB/WebDAV/FTP files)
        // These already have correct filename from intent extras
        val host = uri.host?.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
          Log.d(TAG, "Skipping title fetch for local proxy URL: $uri")
          return@launch
        }

        val url = uri.toString()
        Log.d(TAG, "Fetching title from network stream: $url")

        val betterFilename = HttpUtils.extractFilenameFromUrl(url)
        if (betterFilename != null && betterFilename.isNotBlank() &&
          betterFilename != fileName &&
          betterFilename != uri.host &&
          betterFilename != "Network Stream"
        ) {

          Log.d(TAG, "Found better filename from HTTP headers: $betterFilename")

          // Update fileName
          fileName = betterFilename

          // DO NOT update mediaIdentifier - keep the original identifier for playback state consistency
          // The URI hash in mediaIdentifier ensures position is saved/loaded correctly even if filename changes

          // Update MPV title
          withContext(Dispatchers.Main) {
            MpvGateway.setPropertyString("force-media-title", fileName)
            viewModel.setMediaTitle(fileName)

            // Update media session
            val durationMs = (MpvGateway.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
            updateMediaSessionMetadata(
              title = fileName,
              durationMs = durationMs,
            )

            // Update background service if connected
            if (serviceBound && mediaPlaybackService != null) {
              val artist = runCatching { MpvGateway.getPropertyString("metadata/artist") }.getOrNull() ?: ""
              val thumbnail = runCatching { MpvGateway.grabThumbnail(1080) }.getOrNull()
              mediaPlaybackService?.setMediaInfo(title = fileName, artist = artist, thumbnail = thumbnail)
            }
          }

          // Update recently played with the parsed video title, duration, and file size
          val filePath = when (uri.scheme) {
            "file" -> uri.path ?: uri.toString()
            "content" -> {
              contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else null
              } ?: uri.toString()
            }

            else -> uri.toString()
          }

          // Get duration and file size from MPV
          val updatedDuration = runCatching {
            (MpvGateway.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
          }.getOrDefault(0L)

          val updatedFileSize = runCatching {
            // Try multiple properties to get file size
            MpvGateway.getPropertyDouble("file-size")?.toLong()
              ?: MpvGateway.getPropertyDouble("stream-end")?.toLong()
              ?: 0L
          }.getOrDefault(0L)

          // Get video resolution from MPV
          val updatedWidth = runCatching {
            MpvGateway.getPropertyInt("width") ?: MpvGateway.getPropertyInt("video-params/w") ?: 0
          }.getOrDefault(0)

          val updatedHeight = runCatching {
            MpvGateway.getPropertyInt("height") ?: MpvGateway.getPropertyInt("video-params/h") ?: 0
          }.getOrDefault(0)

          // Update metadata without thumbnail
          runCatching {
            RecentlyPlayedOps.updateVideoMetadata(
              filePath,
              fileName,
              updatedDuration,
              updatedFileSize,
              updatedWidth,
              updatedHeight,
            )
            Log.d(
              TAG,
              "Updated recently played metadata: $fileName (duration: ${updatedDuration}ms, size: ${updatedFileSize}B, resolution: ${updatedWidth}x${updatedHeight}) for $filePath",
            )
          }.onFailure { e ->
            Log.e(TAG, "Error updating video metadata in recently played", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error fetching network stream title", e)
      }
    }
  }

  /**
   * Applies all saved subtitle preferences when a file is loaded.
   * This ensures subtitle customizations (font, colors, position, etc.) persist across videos.
   */
  private fun applySubtitlePreferences() {
    // Typography settings
    MpvGateway.setPropertyString("sub-font", subtitlesPreferences.font.get())
    MpvGateway.setPropertyString("secondary-sub-font", subtitlesPreferences.font.get())
    MpvGateway.setPropertyInt("sub-font-size", subtitlesPreferences.fontSize.get())
    MpvGateway.setPropertyBoolean("sub-bold", subtitlesPreferences.bold.get())
    MpvGateway.setPropertyBoolean("sub-italic", subtitlesPreferences.italic.get())
    MpvGateway.setPropertyString("sub-justify", subtitlesPreferences.justification.get().value)
    MpvGateway.setPropertyString("sub-border-style", subtitlesPreferences.borderStyle.get().value)
    MpvGateway.setPropertyInt("sub-outline-size", subtitlesPreferences.borderSize.get())
    MpvGateway.setPropertyInt("sub-shadow-offset", subtitlesPreferences.shadowOffset.get())

    // Color settings
    MpvGateway.setPropertyString("sub-color", subtitlesPreferences.textColor.get().toColorHexString())
    MpvGateway.setPropertyString("sub-border-color", subtitlesPreferences.borderColor.get().toColorHexString())
    MpvGateway.setPropertyString("sub-back-color", subtitlesPreferences.backgroundColor.get().toColorHexString())

    // Miscellaneous settings
    val overrideAssSubs = subtitlesPreferences.overrideAssSubs.get()
    MpvGateway.setPropertyString("sub-ass-override", if (overrideAssSubs) "force" else "scale")
    MpvGateway.setPropertyString("secondary-sub-ass-override", if (overrideAssSubs) "force" else "scale")

    val scaleByWindow = subtitlesPreferences.scaleByWindow.get()
    val scaleValue = if (scaleByWindow) "yes" else "no"
    MpvGateway.setPropertyString("sub-scale-by-window", scaleValue)
    MpvGateway.setPropertyString("sub-use-margins", scaleValue)

    MpvGateway.setPropertyFloat("sub-scale", subtitlesPreferences.subScale.get())
    MpvGateway.setPropertyInt("sub-pos", subtitlesPreferences.subPos.get())

    Log.d(TAG, "Applied subtitle preferences")
  }

  /**
   * Helper extension function to convert Int color to hex string for MPV
   */
  @OptIn(ExperimentalStdlibApi::class)
  private fun Int.toColorHexString() = "#" + this.toHexString().uppercase()

  /**
   * Saves the current playback state to the database.
   *
   * Captures the current file synchronously, then persists snapshots in order.
   *
   * @param mediaTitle The title of the media being played
   */
  private fun saveVideoPlaybackState(mediaTitle: String) {
    val identifier = mediaIdentifier
    if (identifier.isBlank()) return

    if (!viewModel.playbackSession.loaded ||
      !viewModel.playbackSession.acceptsPath(MpvGateway.getPropertyString("path"))) return
    val pos = MpvGateway.getPropertyInt("time-pos") ?: return
    val duration = MpvGateway.getPropertyInt("duration") ?: 0
    val savePosition = playerPreferences.savePositionOnQuit.get()
    val watchedThreshold = browserPreferences.watchedThreshold.get() / 100f
    val snapshot = PlaybackStateEntity(
      mediaTitle = identifier,
      lastPosition = if (duration > 0 && pos >= duration - 1) 0 else pos,
      playbackSpeed = MpvGateway.getPropertyDouble("speed") ?: DEFAULT_PLAYBACK_SPEED,
      videoZoom = MpvGateway.getPropertyDouble("video-zoom")?.toFloat() ?: 0f,
      sid = player.sid,
      secondarySid = player.secondarySid,
      subDelay = ((MpvGateway.getPropertyDouble("sub-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt(),
      subSpeed = MpvGateway.getPropertyDouble("sub-speed") ?: DEFAULT_SUB_SPEED,
      aid = player.aid,
      audioDelay = ((MpvGateway.getPropertyDouble("audio-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt(),
      externalSubtitles = viewModel.externalSubtitles.joinToString("|"),
      hasBeenWatched = duration > 0 && (pos >= duration - 1 || pos.toFloat() / duration >= watchedThreshold),
    )
    // Preserve saves for different files and their order; never read mpv after suspension.
    val previousSave = savePlaybackStateJob
    savePlaybackStateJob = CoroutineScope(Dispatchers.IO).launch {
      try {
        previousSave?.join()
        val oldState = playbackStateRepository.getVideoDataByTitle(identifier)
        val lastPosition = if (savePosition) snapshot.lastPosition else oldState?.lastPosition ?: 0
        playbackStateRepository.upsert(snapshot.copy(
          lastPosition = lastPosition,
          timeRemaining = (duration - lastPosition).coerceAtLeast(0),
          hasBeenWatched = snapshot.hasBeenWatched || oldState?.hasBeenWatched == true,
        ))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Error saving playback state for $mediaTitle", e)
      }
    }
  }

  /**
   * Loads and applies saved playback state from the database.
   *
   * @param identifier Stable identifier of the media being played
   * @return saved state, or null when no state exists or the lookup fails
   */
  private suspend fun loadVideoPlaybackState(identifier: String): PlaybackStateEntity? {
    if (identifier.isBlank()) return null

    return try {
      playbackStateRepository.getVideoDataByTitle(identifier)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error loading playback state", e)
      null
    }
  }

  private fun isCurrentPlaybackStateLoad(
    generation: Long,
    identifier: String,
  ): Boolean = playbackStateLoadGeneration == generation && mediaIdentifier == identifier

  /**
   * Applies saved playback state to MPV.
   *
   * Restores subtitle delay, audio delay, audio and track selections, and playback speed.
   * Also restores saved time position if enabled.
   *
   * @param state The saved playback state entity
   */
  private fun applyPlaybackState(
    state: PlaybackStateEntity?,
    restorePosition: Boolean,
  ) {
    if (state == null) return

    val subDelay = state.subDelay / DELAY_DIVISOR
    val audioDelay = state.audioDelay / DELAY_DIVISOR

    // Restore external subtitles first
    if (state.externalSubtitles.isNotBlank()) {
      val externalSubUris = state.externalSubtitles.split("|").filter { it.isNotBlank() }
      Log.d(TAG, "Restoring ${externalSubUris.size} external subtitle(s)")

      for (subUri in externalSubUris) {
        viewModel.addSubtitle(Uri.parse(subUri), select = false, silent = true)
      }
    }

    // Always restore subtitle and audio tracks from saved state
    // User's manual selection has highest priority
    if (state.sid > 0) {
      player.sid = state.sid
      Log.d(TAG, "Restored primary subtitle track: ${state.sid} (user selection)")
    }

    if (state.secondarySid > 0) {
      player.secondarySid = state.secondarySid
      Log.d(TAG, "Restored secondary subtitle track: ${state.secondarySid} (user selection)")
    }

    if (state.aid > 0) {
      player.aid = state.aid
      Log.d(TAG, "Restored audio track: ${state.aid} (user selection)")
    }

    MpvGateway.setPropertyDouble("sub-delay", subDelay)
    MpvGateway.setPropertyDouble("speed", state.playbackSpeed)
    MpvGateway.setPropertyDouble("audio-delay", audioDelay)
    MpvGateway.setPropertyDouble("sub-speed", state.subSpeed)

    // Restore video zoom from saved state
    MpvGateway.setPropertyDouble("video-zoom", state.videoZoom.toDouble())
    viewModel.setVideoZoom(state.videoZoom)

    val savedPosition = PlaybackPositionPolicy.positionToRestore(
      savePositionOnQuit = playerPreferences.savePositionOnQuit.get(),
      savedPosition = state.lastPosition,
    )
    if (restorePosition) savedPosition?.let { MpvGateway.setPropertyInt("time-pos", it) }
  }

  /**
   * Applies default settings when no saved state exists.
   *
   * Sets subtitle speed to user default if not present in saved state.
   *
   * @param state The saved playback state entity (null if no saved state)
   */
  private fun applyDefaultSettings(state: PlaybackStateEntity?) {
    if (state == null) {
      val defaultSubSpeed = subtitlesPreferences.defaultSubSpeed.get().toDouble()
      MpvGateway.setPropertyDouble("sub-speed", defaultSubSpeed)
    }
  }

  /**
   * Saves the currently playing file to recently played history.
   *
   * Handles various URI schemes and infers launch source.
   */
  private suspend fun saveRecentlyPlayed() {
    runCatching {
      val uri = extractUriFromIntent(intent)

      if (uri == null) {
        Log.w(TAG, "Cannot save recently played: URI is null")
        return@runCatching
      }

      if (uri.scheme == null) {
        Log.w(TAG, "Cannot save recently played: URI has null scheme: $uri")
        return@runCatching
      }

      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      val launchSource =
        when {
          intent.getStringExtra("launch_source") != null -> intent.getStringExtra("launch_source")
          intent.action == Intent.ACTION_SEND -> "share"
          else -> "normal"
        }

      // Get parsed video title from MPV
      val videoTitle = runCatching {
        MpvGateway.getPropertyString("media-title")
      }.getOrNull()?.takeIf { it.isNotBlank() && it != fileName }

      // Get duration and file size from MPV
      val duration = runCatching {
        (MpvGateway.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      val fileSize = runCatching {
        // Try multiple properties to get file size
        MpvGateway.getPropertyDouble("file-size")?.toLong()
          ?: MpvGateway.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        MpvGateway.getPropertyInt("width") ?: MpvGateway.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MpvGateway.getPropertyInt("height") ?: MpvGateway.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = fileName,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = launchSource,
      )

      Log.d(TAG, "Saved recently played: $filePath")
      Log.d(TAG, "  - fileName: $fileName")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - source: $launchSource")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played", e)
    }
  }

  // ==================== Intent and Result Management ====================

  /**
   * Sets the result intent with current playback position and duration.
   * Called when activity is finishing to return data to caller.
   */
  private fun setReturnIntent() {
    Log.d(TAG, "Setting return intent")

    val resultIntent =
      Intent(RESULT_INTENT).apply {
        viewModel.pos?.let { putExtra("position", it * MILLISECONDS_TO_SECONDS) }
        viewModel.duration?.let { putExtra("duration", it * MILLISECONDS_TO_SECONDS) }
      }

    setResult(RESULT_OK, resultIntent)
  }

  /**
   * Handles new intents to load a different file without recreating the activity.
   *
   * @param intent The new intent
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (!mpvInitialized) {
      if (parsePathFromIntent(intent) != null) setIntent(intent)
      return
    }

    // Use the same parser as playback: SEND carries EXTRA_STREAM/EXTRA_TEXT,
    // and explicit callers can supply "uri" without Intent.data.
    val mediaPath = parsePathFromIntent(intent)
    if (mediaPath == null && !intent.hasExtra("playlist") && !intent.hasExtra("playlist_id")) return
    mediaPath?.let(Uri::parse)?.let(::playbackProxyStreamId)?.let(playbackStreams::remember)

    saveVideoPlaybackState(fileName)
    // Invalidate old playback before replacing its identity; resolution happens on IO.
    val playablePath = mediaPath ?: return
    playlistLoadJob?.cancel()
    isM3uPlaylist = false
    viewModel.beginFileChange(playablePath)
    isReady = false
    // Update the intent first so getFileName uses the new intent data
    setIntent(intent)
    initialIntentExtrasApplied = false
    playbackStateLoadGeneration++

    // Check if this intent has playlist information
    val hasPlaylistExtras = intent.hasExtra("playlist_id") ||
      intent.hasExtra("playlist")

    // Load playlist from intent extras first (fast path)
    val playlistFromIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    // New media replaces the old queue, including SEND intents without data.
    // Notification/PiP returns were handled above without changing the queue.
    if (hasPlaylistExtras || playlistFromIntent.isNotEmpty() || mediaPath != null) {
      val newPlaylistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
      playlistId = newPlaylistId
      playlistIndex = intent.getIntExtra("playlist_index", 0)
      playlistWindowOffset = 0
      playlistTotalCount = -1
      playlist = playlistFromIntent
    }

    val initialItem = playlist.getOrNull(playlistIndex)
    preparePlayback(
      playablePath,
      readName = { initialItem?.let(::getFileNameFromUri) ?: getFileName(intent) },
      identify = { name -> initialItem?.let { getMediaIdentifierFromUri(it, name) }
        ?: getMediaIdentifier(intent, name) },
      configure = { setHttpHeadersFromExtras(intent.extras) },
    )
  }

  // ==================== Picture-in-Picture Management ====================

  /**
   * Called when Picture-in-Picture mode changes.
   * Updates UI visibility and window configuration.
   *
   * @param isInPictureInPictureMode true if entering PiP, false if exiting
   * @param newConfig The new configuration
   */
  @RequiresApi(Build.VERSION_CODES.P)
  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

    pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode)

    binding.controls.alpha = if (isInPictureInPictureMode) 0f else 1f

    runCatching {
      if (isInPictureInPictureMode) {
        enterPipUIMode()
      } else {
        exitPipUIMode()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error handling PiP mode change", e)
    }
  }

  /**
   * Configures window for Picture-in-Picture mode.
   * Shows system UI and navigation bars.
   */
  private fun enterPipUIMode() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    WindowCompat.setDecorFitsSystemWindows(window, true)
    try {
      windowInsetsController.apply {
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to show system bars for PiP mode", e)
    }
  }

  /**
   * Restores window configuration when exiting Picture-in-Picture mode.
   * Hides system UI for immersive playback.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  private fun exitPipUIMode() {
    setupWindowFlags()
    setupSystemUI()
  }

  /**
   * Enters Picture-in-Picture mode and hides all overlay controls.
   */
  fun enterPipModeHidingOverlay() {
    runCatching {
      enterPipUIMode()
    }.onFailure { e ->
      Log.e(TAG, "Error entering PiP mode with hidden overlay", e)
    }

    binding.controls.alpha = 0f

    pipHelper.enterPipMode()
  }

  // ==================== Orientation Management ====================

  /**
   * Sets the screen orientation based on user preferences.
   *
   * IMPORTANT: Preferences are the single source of truth for orientation.
   * This method applies the preference value when videos load.
   * The rotation button temporarily overrides this without changing preferences.
   *
   * For "Video" orientation mode, this will wait for video-params/aspect to update
   * to the correct orientation, starting with landscape as fallback.
   */
  private fun setOrientation() {
    val orientationPref = playerPreferences.orientation.get()

    requestedOrientation =
      when (orientationPref) {
        PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        PlayerOrientation.Video -> {
          // For video orientation, check if aspect is available
          val aspect = runCatching { player.getVideoOutAspect() }.getOrNull()
          Log.d(TAG, "setOrientation - Video mode: aspect=$aspect")
          if (aspect == null || aspect <= 0.0) {
            // Aspect not available yet - wait for video-params/aspect update
            Log.d(TAG, "setOrientation - Aspect not available, defaulting to landscape")
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
          } else {
            // Aspect available - set correct orientation now
            val orientation = if (aspect > 1.0) {
              Log.d(TAG, "setOrientation - Aspect $aspect > 1.0, setting landscape")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
              Log.d(TAG, "setOrientation - Aspect $aspect <= 1.0, setting portrait")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            orientation
          }
        }
        PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
  }

  // ==================== Key Event Handling ====================

  /**
   * Handles hardware key down events for player control.
   * Supports D-pad navigation, media keys, and volume controls.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
  override fun onKeyDown(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    if (!mpvInitialized) return super.onKeyDown(keyCode, event)
    val isTrackSheetOpen =
      viewModel.sheetShown.value == Sheets.SubtitleTracks ||
        viewModel.sheetShown.value == Sheets.AudioTracks
    val isNoSheetOpen = viewModel.sheetShown.value == Sheets.None

    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_UP -> {
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_LEFT,
        -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }

        if (isNoSheetOpen) {
          when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
              viewModel.handleRightDoubleTap()
              return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
              viewModel.handleLeftDoubleTap()
              return true
            }
          }
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_SPACE -> {
        viewModel.pauseUnpause()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        viewModel.changeVolumeBy(1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        viewModel.changeVolumeBy(-1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_STOP -> {
        finishAndRemoveTask()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_REWIND -> {
        viewModel.handleLeftDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
        viewModel.handleRightDoubleTap()
        return true
      }

      else -> {
        event?.let { player.onKey(it) }
        return super.onKeyDown(keyCode, event)
      }
    }
  }

  /**
   * Handles hardware key up events for player control.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  override fun onKeyUp(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    if (!mpvInitialized) return super.onKeyUp(keyCode, event)
    event?.let {
      if (player.onKey(it)) return true
    }
    return super.onKeyUp(keyCode, event)
  }

  // ==================== System UI Management ====================

  /**
   * Restores system UI to normal state (shows status and navigation bars).
   * Called when finishing the activity to return to normal Android UI.
   */

  // ==================== MediaSession ====================

  /**
   * Initializes MediaSession for integration with system media controls.
   * Supports Android Auto, Wear OS, Bluetooth controls, and notification controls.
   */
  private fun setupMediaSession() {
    runCatching {
      mediaSession =
        MediaSession(this, TAG).apply {
          setCallback(
            object : MediaSession.Callback() {
              override fun onPlay() {
                viewModel.unpause()
                updateMediaSessionPlaybackState(isPlaying = true)
              }

              override fun onPause() {
                viewModel.pause()
                updateMediaSessionPlaybackState(isPlaying = false)
              }

              override fun onSeekTo(pos: Long) {
                viewModel.seekTo((pos / 1000).toInt())
                updateMediaSessionPlaybackState(isPlaying = viewModel.paused == false)
              }
            },
          )
          isActive = true
        }
      playbackStateBuilder =
        PlaybackState
          .Builder()
          .setActions(
            PlaybackState.ACTION_PLAY or
              PlaybackState.ACTION_PAUSE or
              PlaybackState.ACTION_PLAY_PAUSE or
              PlaybackState.ACTION_SEEK_TO,
          )
      mediaSessionInitialized = true
    }.onFailure { e ->
      Log.e(TAG, "Failed to initialize MediaSession", e)
      mediaSessionInitialized = false
    }
  }

  /**
   * Updates MediaSession playback state (playing/paused).
   *
   * @param isPlaying true if currently playing, false if paused
   */
  private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
    if (!mediaSessionInitialized) return
    runCatching {
      val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
      val positionMs = (viewModel.pos ?: 0) * 1000L
      mediaSession.setPlaybackState(
        playbackStateBuilder
          .setState(state, positionMs, if (isPlaying) 1.0f else 0f)
          .build(),
      )
    }.onFailure { e -> Log.e(TAG, "Error updating playback state", e) }
  }

  /**
   * Updates MediaSession metadata (title, duration, etc.).
   *
   * @param title The media title
   * @param durationMs The media duration in milliseconds
   */
  private fun updateMediaSessionMetadata(
    title: String,
    durationMs: Long,
  ) {
    if (!mediaSessionInitialized) return
    runCatching {
      val metadata =
        MediaMetadata
          .Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, title)
          .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
          .build()
      mediaSession.setMetadata(metadata)
    }.onFailure { e -> Log.e(TAG, "Error updating metadata", e) }
  }

  /**
   * Releases MediaSession resources.
   * Called during activity cleanup.
   */
  private fun releaseMediaSession() {
    if (!mediaSessionInitialized) return
    runCatching {
      mediaSession.isActive = false
      mediaSession.release()
    }.onFailure { e -> Log.e(TAG, "Error releasing MediaSession", e) }
    mediaSessionInitialized = false
  }

  // ==================== Background Playback Service ====================

  /**
   * Service connection for binding to background playback service.
   */
  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
      ) {
        val binder = service as? MediaPlaybackService.MediaPlaybackBinder ?: return
        mediaPlaybackService = binder.getService()
        mediaPlaybackService?.attachControls(viewModel.controls)
        serviceBound = true
        Log.d(TAG, "Service connected")
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "Service disconnected")
        mediaPlaybackService = null
        serviceBound = false
      }
    }

  /**
   * Starts the background playback service and binds to it.
   *
   * This should only be called if a video is loaded and playback is initialized.
   * Responsible for starting and binding to the MediaPlaybackService, which
   * handles background playback.
   */
  private fun startBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot start background playback: video not ready")
      return
    }

    // Prevent starting service multiple times
    if (serviceBound) {
      Log.d(TAG, "Service already bound, skipping start")
      return
    }

    Log.d(TAG, "Starting background playback for: $fileName")
    
    // Ensure notification channel exists
    MediaPlaybackService.createNotificationChannel(this)
    
    // Get media info before starting service
    val artist = runCatching { MpvGateway.getPropertyString("metadata/artist") }.getOrNull() ?: ""
    val thumbnail = runCatching { MpvGateway.grabThumbnail(1080) }.getOrNull()
    
    // Pass media info via intent extras
    val intent = Intent(this, MediaPlaybackService::class.java).apply {
      putExtra("media_title", fileName)
      putExtra("media_artist", artist)
    }
    
    // Store thumbnail in companion object for service to access
    MediaPlaybackService.thumbnail = thumbnail
    
    try {
      startForegroundService(intent)
      bindService(intent, serviceConnection, BIND_AUTO_CREATE)
      Log.d(TAG, "Service start and bind initiated")
    } catch (e: Exception) {
      Log.e(TAG, "Error starting/binding service", e)
    }
  }

  /**
   * Stops the background playback service and unbinds from it.
   *
   * Called when the activity is destroyed to remove the notification.
   */
  private fun endBackgroundPlayback() {
    Log.d(TAG, "Ending background playback service")
    
    mediaPlaybackService?.attachControls(null)
    if (serviceBound) {
      try {
        unbindService(serviceConnection)
        Log.d(TAG, "Service unbound successfully")
      } catch (e: Exception) {
        Log.e(TAG, "Error unbinding service", e)
      }
      serviceBound = false
    }
    
    // Stop the service which will trigger its onDestroy and cleanup
    try {
      stopService(Intent(this, MediaPlaybackService::class.java))
      Log.d(TAG, "Stop service command sent")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping service", e)
    }
    
    mediaPlaybackService = null
  }

  /**
   * Manually triggers background playback when the user clicks the background playback button.
   * This works independently of the automaticBackgroundPlayback preference.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  fun triggerBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot trigger background playback: video not ready")
      return
    }

    Log.d(TAG, "User triggered background playback")
    
    // Set flag to enable background playback (same logic as automatic)
    isManualBackgroundPlayback = true
    
    // Restore system UI before going to background
    restoreSystemUI()
    
    // Move to background by going to home screen (same behavior as automatic)
    val intent = Intent(Intent.ACTION_MAIN).apply {
      addCategory(Intent.CATEGORY_HOME)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
  }

  // ==================== PlayerHost ====================
  override val context: Context
    get() = this
  override val windowInsetsController: WindowInsetsControllerCompat
    get() = WindowCompat.getInsetsController(window, window.decorView)
  override val hostWindow: android.view.Window
    get() = window
  override val hostWindowManager: WindowManager
    get() = windowManager
  override val hostContentResolver: android.content.ContentResolver
    get() = contentResolver
  override val audioManager: AudioManager
    get() = getSystemService(AUDIO_SERVICE) as AudioManager
  override var hostRequestedOrientation: Int
    get() = requestedOrientation
    set(value) {
      requestedOrientation = value
    }

  // ==================== Playlist Management ====================

  /**
   * Check if there's a next video in the playlist
   */
  fun hasNext(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "next" (loops back to beginning)
    if (viewModel.shouldRepeatPlaylist()) return true

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition < shuffledIndices.size - 1
    } else {
      playlistIndex < effectiveSize - 1
    }
  }

  /**
   * Check if there's a previous video in the playlist
   */
  fun hasPrevious(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "previous" (loops back to end)
    if (viewModel.shouldRepeatPlaylist()) return true

    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition > 0
    } else {
      playlistIndex > 0
    }
  }

  /**
   * Generate shuffled indices for the playlist
   */
  private fun generateShuffledIndices() {
    if (playlist.isEmpty()) return

    // Create a list of all indices except the current one
    val indices = playlist.indices.filter { it != playlistIndex }.toMutableList()
    indices.shuffle()

    // Put current index at the beginning
    shuffledIndices = listOf(playlistIndex) + indices
    shuffledPosition = 0
  }

  /**
   * Called when shuffle is toggled on/off
   */
  fun onShuffleToggled(enabled: Boolean) {
    if (enabled && playlist.isNotEmpty()) {
      generateShuffledIndices()
    } else {
      shuffledIndices = emptyList()
      shuffledPosition = 0
    }
  }

  /**
   * Play the next video in the playlist
   */
  fun playNext() {
    if (playlist.isEmpty()) return

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to next position
      if (shuffledPosition < shuffledIndices.size - 1) {
        shuffledPosition++
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of shuffled playlist with repeat ALL: regenerate and restart
        generateShuffledIndices()
        shuffledPosition = 0
        playlistIndex = shuffledIndices[0]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex < effectiveSize - 1) {
        playlistIndex++
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of playlist with repeat ALL: restart from beginning
        playlistIndex = 0
        loadPlaylistItem(0)
      }
    }
  }

  /**
   * Play the previous video in the playlist
   */
  fun playPrevious() {
    if (playlist.isEmpty()) return

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to previous position
      if (shuffledPosition > 0) {
        shuffledPosition--
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of shuffled playlist with repeat ALL: go to end
        shuffledPosition = shuffledIndices.size - 1
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex > 0) {
        playlistIndex--
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of playlist with repeat ALL: go to last item
        playlistIndex = effectiveSize - 1
        loadPlaylistItem(playlistIndex)
      }
    }
  }

  /**
   * Load a playlist item by index
   */
  private fun loadPlaylistItem(index: Int) {
    // All items are loaded - just validate index and load directly
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }
    loadPlaylistItemInternal(index)
  }

  /**
   * Internal method to load a playlist item
   */
  private fun loadPlaylistItemInternal(index: Int) {
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }

    // Stop watching the old item immediately. The new watcher is started from
    // the FILE_LOADED event once MPV has switched to the new item.
    cancelNextPrefetch()
    playlistLoadJob?.cancel()
    playbackStateLoadGeneration++

    // Save current video's playback state before switching
    if (fileName.isNotBlank()) {
      saveVideoPlaybackState(fileName)
    }

    val uri = playlist[index]
    val playableUri = uri.toString()
    viewModel.beginFileChange(playableUri)
    isReady = false

    // Update playlist index
    playlistIndex = index

    preparePlayback(
      playableUri,
      readName = { getFileNameFromUri(uri) },
      identify = { getMediaIdentifierFromUri(uri, it) },
      configure = {
        setHttpHeadersForUri(uri)
        val isM3U = uri.toString().contains(".m3u", ignoreCase = true)
        if (!isM3U) {
          MpvGateway.setPropertyString("force-media-title", fileName)
          viewModel.setMediaTitle(fileName)
        }
      },
    )

    // Update playlist play history if this is a custom playlist
    playlistId?.let { id ->
      lifecycleScope.launch(Dispatchers.IO) {
        val filePath = when (uri.scheme) {
          "file" -> uri.path ?: uri.toString()
          "content" -> {
            contentResolver.query(
              uri,
              arrayOf(MediaStore.MediaColumns.DATA),
              null,
              null,
              null,
            )?.use { cursor ->
              if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (columnIndex != -1) cursor.getString(columnIndex) else null
              } else null
            } ?: uri.toString()
          }

          else -> uri.toString()
        }

        runCatching {
          playlistRepository.updatePlayHistory(id, filePath)
          Log.d(TAG, "Updated playlist history for: $filePath in playlist $id")
        }.onFailure { e ->
          Log.e(TAG, "Error updating playlist history", e)
        }
      }
    }

    // Native FILE_LOADED publishes title/duration; no fixed-delay metadata read.

  }

  /**
   * Watches playback progress and, shortly before the current file ends,
   * pre-fetches the next playlist item's file header into the proxy cache so
   * the track switch doesn't stall on downloading the container header.
   */
  private fun startNextPrefetch() {
    // A file load starts a new watcher. Cancelling here also covers duplicate
    // FILE_LOADED callbacks and playlist changes while the old item is still
    // waiting for its duration.
    cancelNextPrefetch()

    if (playlist.size <= 1) return
    val isNetworkPlayback = playlist.any { it.host == "127.0.0.1" || it.host == "localhost" }
    if (!isNetworkPlayback) return

    val watchedIndex = playlistIndex
    nextPrefetchJob = lifecycleScope.launch(Dispatchers.IO) {
      while (isActive) {
        // Do not let a watcher for the previous item prefetch after a manual
        // jump or an automatic track switch.
        if (playlistIndex != watchedIndex) return@launch
        val duration = MpvGateway.getPropertyDouble("duration") ?: 0.0
        val pos = MpvGateway.getPropertyDouble("time-pos") ?: 0.0
        if (duration > 0) {
          if (duration - pos <= NEXT_PREFETCH_SECONDS && canPrefetchNext(watchedIndex)) {
            prefetchNextPlaylistItem(watchedIndex)
            return@launch
          }
        } else {
          // Duration unknown yet - give the file a moment to load before polling
          kotlinx.coroutines.delay(1000)
          continue
        }
        kotlinx.coroutines.delay(500)
      }
    }
  }

  /**
   * Pre-fetch the file header (first ~2MB) of the next playlist item through
   * the proxy, so mpv opens it instantly at switch time. Handles repeat-all
   * wrap-around and follows the current shuffled order when one is active.
   */
  private suspend fun prefetchNextPlaylistItem(currentIndex: Int) {
    val next = nextPlaylistIndexForPrefetch(currentIndex) ?: return

    val uri = playlist.getOrNull(next) ?: return
    val host = uri.host
    if (host != "127.0.0.1" && host != "localhost") return

    val streamId = uri.path?.removePrefix("/")?.substringBefore("/") ?: return
    val info = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance().getStreamInfo(streamId)
      ?: return

    val streamKey = app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkMetadataProbe
      .cacheIdentity(info.connection, info.file)
    // add() is atomic, so a cancelled watcher that is still finishing an
    // in-flight request cannot start a duplicate request for the same file.
    if (!inFlightPrefetchKeys.tryStart(streamKey)) return

    val file = info.file
    try {
      val prefetched = app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkMetadataProbe
        .prefetchHeader(info.connection, file) { canPrefetchNext(currentIndex) }
      if (prefetched) {
        Log.d(TAG, "Prefetched next item header: ${info.filePath}")
      } else {
        Log.w(TAG, "Prefetch returned an unsuccessful response: ${info.filePath}")
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to prefetch next item header: ${info.filePath}", e)
    } finally {
      // The header LRU may evict this item later; only suppress overlap, not
      // future prefetches after eviction.
      inFlightPrefetchKeys.complete(streamKey)
    }
  }

  private fun cancelNextPrefetch() {
    nextPrefetchJob?.cancel()
    nextPrefetchJob = null
  }

  private fun canPrefetchNext(index: Int): Boolean {
    if (!isReady || playlistIndex != index) return false
    val remaining = (MpvGateway.getPropertyDouble("duration") ?: return false) -
      (MpvGateway.getPropertyDouble("time-pos") ?: return false)
    return app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkPlaybackPolicy.shared.canPrefetch(
      remaining,
      MpvGateway.getPropertyDouble("demuxer-cache-duration") ?: 0.0,
      MpvGateway.getPropertyBoolean("seeking") ?: false,
      MpvGateway.getPropertyBoolean("paused-for-cache") ?: false,
    )
  }

  /**
   * Resolve the actual next item in playback order. In shuffle mode the
   * shuffled index list is the source of truth; playlistIndex + 1 is not.
   * At the end of a shuffled repeat cycle we skip prefetch because the next
   * cycle is regenerated randomly and is not known yet.
   */
  private fun nextPlaylistIndexForPrefetch(currentIndex: Int): Int? {
    return PlaylistPrefetchOrder.nextIndex(
      currentIndex = currentIndex,
      playlistSize = playlist.size,
      shuffleEnabled = viewModel.shuffleEnabled.value,
      shuffledIndices = shuffledIndices,
      repeatAll = viewModel.repeatMode.value == app.marlboroadvance.mpvex.ui.player.RepeatMode.ALL,
    )
  }

  /**
   * Get file name from URI (used for playlist items)
   */
  private fun getFileNameFromUri(uri: Uri): String {
    // Local proxy URLs carry a stream id in the path, not the real filename,
    // so resolve the actual network file name from the proxy registry first.
    resolveProxyFileName(uri)?.let { return it }
    getDisplayNameFromUri(uri)?.let { return it }
    return extractFileNameFromUri(uri)
  }

  /**
   * Return the proxy's source metadata for a local proxy URI, if available.
   * The path segment is only an ephemeral registry key and is never a media
   * identifier or subtitle lookup path by itself.
   */
  private fun resolveProxyStreamInfo(
    uri: Uri,
  ): app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.StreamInfo? {
    val host = uri.host ?: return null
    if (host != "127.0.0.1" && host != "localhost") return null
    val streamId = uri.path?.removePrefix("/")?.substringBefore("/") ?: return null
    if (streamId.isBlank()) return null
    return app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
      .getInstance()
      .getStreamInfo(streamId)
  }

  /**
   * For local proxy URLs (http://127.0.0.1:port/<streamId>) the path segment is
   * a generated stream id, so the real file name is looked up from the proxy.
   */
  private fun resolveProxyFileName(uri: Uri): String? {
    val info = resolveProxyStreamInfo(uri) ?: return null
    val name = info.filePath.substringAfterLast('/').ifBlank { info.filePath }
    return try {
      java.net.URLDecoder.decode(name, "UTF-8")
    } catch (e: Exception) {
      name
    }
  }

  /**
   * Get the current video title for controls display.
   * Used as a fallback when MPV hasn't set the media-title property yet.
   * For m3u/m3u8 streams, returns the raw media-title from MPV instead of parsing.
   */
  fun getTitleForControls(): String {
    // For m3u/m3u8 streams, use MPV's raw media-title directly
    if (isCurrentStreamM3U()) {
      val rawTitle = MpvGateway.getPropertyString("media-title")
      if (!rawTitle.isNullOrBlank()) {
        return rawTitle
      }
    }
    return fileName
  }

  /**
   * Check if the currently playing media is an m3u or m3u8 stream.
   * Checks both the intent URI and the current playlist item if playing from a playlist.
   */
  private fun isCurrentStreamM3U(): Boolean {
    // First check the intent URI
    val uri = extractUriFromIntent(intent)
    if (uri != null && isUriM3U(uri)) {
      return true
    }

    // Also check the current playlist item if playing from a playlist
    if (playlist.isNotEmpty() && playlistIndex >= 0 && playlistIndex < playlist.size) {
      return isUriM3U(playlist[playlistIndex])
    }

    return false
  }

  /**
   * Check if a specific URI is an m3u or m3u8 file/stream.
   */
  private fun isUriM3U(uri: Uri): Boolean {
    val lowerUrl = uri.toString().lowercase()
    return lowerUrl.contains(".m3u8") || lowerUrl.contains(".m3u") ||
      lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".m3u")
  }

  /**
   * Save recently played for a specific URI
   */
  private suspend fun saveRecentlyPlayedForUri(
    uri: Uri,
    name: String,
  ) {
    runCatching {
      val filePath =
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }

      // Get parsed video title from MPV
      val videoTitle = runCatching {
        MpvGateway.getPropertyString("media-title")
      }.getOrNull()?.takeIf { it.isNotBlank() && it != name }

      // Get duration and file size from MPV
      val duration = runCatching {
        (MpvGateway.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      val fileSize = runCatching {
        // Try multiple properties to get file size
        MpvGateway.getPropertyDouble("file-size")?.toLong()
          ?: MpvGateway.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        MpvGateway.getPropertyInt("width") ?: MpvGateway.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MpvGateway.getPropertyInt("height") ?: MpvGateway.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = name,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = "playlist",
        playlistId = playlistId,
      )

      Log.d(TAG, "Saved recently played (playlist): $filePath")
      Log.d(TAG, "  - fileName: $name")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - playlistId: $playlistId")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played for playlist item", e)
    }
  }

  /**
   * Generate a unique identifier for this media for playback state/history.
   *
   * For local/offline files, uses fileName (display name or path).
   * For network streams via proxy (SMB/WebDAV/FTP), uses the stable network file path from intent extras.
   * For other network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifier(intent: Intent, fileName: String): String {
    // Prefer the proxy registry over intent extras. The extras describe only
    // the item that launched the activity, while a playlist may now be on a
    // different proxy stream.
    val uri = extractUriFromIntent(intent)
    uri?.let { currentUri ->
      resolveProxyStreamInfo(currentUri)?.let { info ->
        return NetworkMediaIdentity.forFile(info.connection.id, info.filePath)
      }
    }

    // Check if this is a network file played via proxy (SMB/WebDAV/FTP)
    // Use the stable network file path instead of the temporary proxy URL
    val networkFilePath = intent.getStringExtra("network_file_path")
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

    if (networkFilePath != null && networkConnectionId != -1L) {
      // For network files via proxy: use connection ID + file path for stable identifier
      val identifier = NetworkMediaIdentity.forFile(networkConnectionId, networkFilePath)
      Log.d(
        TAG,
        "Using network file identifier: $identifier (connection: $networkConnectionId, path: $networkFilePath)",
      )
      return identifier
    }

    return if (uri != null && (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms")) {
      // For remote protocols: hash the URI so position is per-episode or per-stream.
      "${fileName}_${uri.toString().hashCode()}"
    } else {
      // For local/file uris and unknown: just use fileName.
      fileName
    }
  }

  /**
   * Generate a unique identifier for this media from a URI and name.
   *
   * For local/offline files, uses fileName (display name or path).
   * For network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifierFromUri(uri: Uri, fileName: String): String {
    resolveProxyStreamInfo(uri)?.let { info ->
      val identifier = NetworkMediaIdentity.forFile(info.connection.id, info.filePath)
      Log.d(TAG, "Using proxy network file identifier: $identifier (connection: ${info.connection.id}, path: ${info.filePath})")
      return identifier
    }

    return if (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms") {
      "${fileName}_${uri.toString().hashCode()}"
    } else {
      fileName
    }
  }

  private fun preparePlayback(
    path: String,
    readName: () -> String,
    identify: (String) -> String,
    configure: () -> Unit,
  ) {
    val generation = viewModel.playbackSession.generation
    mediaPreparationJob?.cancel()
    mediaPreparationJob = lifecycleScope.launch {
      try {
        val (name, identifier) = withContext(Dispatchers.IO) {
          val name = readName().ifBlank { Uri.parse(path).lastPathSegment ?: "Unknown Video" }
          name to identify(name)
        }
        if (generation != viewModel.playbackSession.generation || isFinishing) return@launch
        fileName = name
        mediaIdentifier = identifier
        configure()
        player.whenSurfaceReady {
          if (generation == viewModel.playbackSession.generation && !isFinishing) viewModel.loadFile(path)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        viewModel.failPreparation(generation)
        Log.e(TAG, "Unable to prepare playback metadata")
      }
    }
  }

  private fun loadDeferredPlaylist() {
    if (playlist.isNotEmpty()) return
    val pid = playlistId
    if (pid == null) {
      if (playerPreferences.playlistMode.get()) {
        MpvGateway.getPropertyString("path")?.let(::generatePlaylistFromFolder)
      }
      return
    }
    val generation = viewModel.playbackSession.generation
    playlistLoadJob?.cancel()
    playlistLoadJob = lifecycleScope.launch(Dispatchers.IO) {
      try {
        val entity = playlistRepository.getPlaylistById(pid)
        val items = playlistRepository.getPlaylistItemsAsUris(pid)
        withContext(Dispatchers.Main) {
          if (generation != viewModel.playbackSession.generation || playlistId != pid || isFinishing) {
            return@withContext
          }
          isM3uPlaylist = entity?.isM3uPlaylist ?: false
          playlist = items
          playlistWindowOffset = 0
          playlistTotalCount = items.size
          if (viewModel.shuffleEnabled.value) onShuffleToggled(true)
          if (isReady) startNextPrefetch()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        Log.e(TAG, "Unable to load playback queue")
      }
    }
  }

  private fun generatePlaylistFromFolder(currentPath: String) {
    val generation = viewModel.playbackSession.generation
    val launchSource = intent.getStringExtra("launch_source") ?: ""
    playlistLoadJob?.cancel()
    playlistLoadJob = lifecycleScope.launch(Dispatchers.IO) {
      runCatching {
        val currentFile = File(currentPath)
        if (!currentFile.exists()) return@runCatching

        val parentFolder = currentFile.parentFile ?: return@runCatching

        val videoExtensions = FileTypeUtils.VIDEO_EXTENSIONS

        val files = parentFolder.listFiles { file ->
          file.isFile &&
            FileTypeUtils.isVideoFile(file) &&
            !FileFilterUtils.shouldSkipFile(file)
        } ?: return@runCatching

        val siblingFiles = if (launchSource == "video_list" || launchSource == "recently_played_button" || launchSource == "first_video_button") {
          val videoSortType = browserPreferences.videoSortType.get()
          val videoSortOrder = browserPreferences.videoSortOrder.get()
          val bucketId = parentFolder.absolutePath.replace("\\", "/")
          val videosInFolder =
            app.marlboroadvance.mpvex.repository.MediaFileRepository.getVideosForBuckets(
              context,
              setOf(bucketId)
            )
          val sortedVideos = app.marlboroadvance.mpvex.utils.sort.SortUtils.sortVideos(videosInFolder, videoSortType, videoSortOrder)
          sortedVideos.mapNotNull { video -> files.find { it.absolutePath == video.path } }
        } else {
          files.sortedWith { f1, f2 -> app.marlboroadvance.mpvex.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT.compare(f1.name, f2.name) }
        }

        if (siblingFiles.size <= 1) return@runCatching

        val newPlaylist = siblingFiles.map { it.toUri() }

        val newIndex = siblingFiles.indexOfFirst { it.absolutePath == currentFile.absolutePath }

        if (newIndex != -1) {
          withContext(Dispatchers.Main) {
            if (generation != viewModel.playbackSession.generation || isFinishing) return@withContext
            playlist = newPlaylist
            playlistIndex = newIndex
            Log.d(TAG, "Auto-playlist generated: ${playlist.size} videos")
            // Re-initialize shuffle now that playlist is available
            if (viewModel.shuffleEnabled.value) {
              onShuffleToggled(true)
            }
          }
        }
      }.onFailure { e ->
        Log.e(TAG, "Failed to auto-generate playlist", e)
      }
    }
  }

  /**
   * Check if the current playlist is an M3U playlist (sourced from database).
   */
  fun isCurrentPlaylistM3U(): Boolean = isM3uPlaylist


  private fun playbackProxyStreamId(uri: Uri): String? {
    val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
      .existingInstance() ?: return null
    if (uri.host !in setOf("127.0.0.1", "localhost") || uri.port != proxy.listeningPort) return null
    val id = uri.pathSegments.firstOrNull() ?: return null
    return id.takeIf { proxy.getStreamInfo(it) != null }
  }

  companion object {
    // mpv and the proxy survive Activity recreation; ownership must survive it too.
    private val playbackStreams = app.marlboroadvance.mpvex.ui.browser.networkstreaming.PlaybackStreamOwnership()

    /**
     * Intent action used to return playback result data to the calling activity.
     */
    private const val RESULT_INTENT = "app.marlboroadvance.mpvex.ui.player.PlayerActivity.result"

    /**
     * Trigger the next-item header prefetch when this many seconds remain in
     * the current file.
     */
    private const val NEXT_PREFETCH_SECONDS = 20

    /**
     * Constant for "brightness not set".
     */
    private const val BRIGHTNESS_NOT_SET = -1f

    /**
     * Constant used when playback position is not set.
     */
    private const val POSITION_NOT_SET = 0

    /**
     * Maximum volume for MPV in percent.
     */
    private const val MAX_MPV_VOLUME = 100

    /**
     * Milliseconds-to-seconds conversion factor.
     */
    private const val MILLISECONDS_TO_SECONDS = 1000

    /**
     * Factor to divide subtitle and audio delays to convert from ms to seconds.
     */
    private const val DELAY_DIVISOR = 1000.0

    /**
     * Default playback speed (1.0 = normal).
     */
    private const val DEFAULT_PLAYBACK_SPEED = 1.0

    /**
     * Default subtitle speed (1.0 = normal).
     */
    private const val DEFAULT_SUB_SPEED = 1.0

    /**
     * General tag for logging from PlayerActivity.
     */
    const val TAG = "mpvex"
  }
}
