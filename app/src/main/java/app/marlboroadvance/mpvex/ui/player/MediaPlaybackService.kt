package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import app.marlboroadvance.mpvex.R
import org.koin.core.component.KoinComponent

/**
 * Background playback service for mpv with MediaSession integration.
 */
class MediaPlaybackService :
  MediaBrowserServiceCompat(),
  KoinComponent {
  companion object {
    private const val TAG = "MediaPlaybackService"
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_CHANNEL_ID = "mpvex_playback_channel"

    @Volatile
    internal var thumbnail: Bitmap? = null
    
    @Volatile
    private var isServiceRunning = false

    fun createNotificationChannel(context: Context) {
      val channel =
        NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          context.getString(R.string.notification_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = context.getString(R.string.notification_channel_description)
          setShowBadge(false)
          enableLights(false)
          enableVibration(false)
        }

      (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
        .createNotificationChannel(channel)
    }
  }

  private val binder = MediaPlaybackBinder()
  private lateinit var mediaSession: MediaSessionCompat
  private val serviceScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
  )
  private var controls: PlaybackControls? = null
  private var stateJob: kotlinx.coroutines.Job? = null
  private var playback = PlaybackUiState()

  internal fun attachControls(controls: PlaybackControls?) {
    stateJob?.cancel()
    this.controls = controls
    if (controls == null) return
    stateJob = serviceScope.launch {
      controls.state.collect { next ->
        val important = playback.phase != next.phase || playback.paused != next.paused ||
          playback.generation != next.generation || playback.duration != next.duration
        playback = next
        paused = next.paused
        val now = android.os.SystemClock.elapsedRealtime()
        if (important || now - lastNotificationUpdateTime >= notificationUpdateIntervalMs) {
          lastNotificationUpdateTime = now
          updateMediaSession()
        }
      }
    }
  }

  private var mediaTitle = ""
  private var mediaArtist = ""
  private var paused = true
  private var lastNotificationUpdateTime = 0L
  private val notificationUpdateIntervalMs = 1000L // Update notification every 1 second

  inner class MediaPlaybackBinder : Binder() {
    fun getService() = this@MediaPlaybackService
  }

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")
    
    isServiceRunning = true

    // Ensure notification channel exists before starting foreground service
    createNotificationChannel(this)

    setupMediaSession()
    
  }

  override fun onBind(intent: Intent): IBinder = binder

  @SuppressLint("ForegroundServiceType")
  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    Log.d(TAG, "Service starting with startId: $startId")

    // Handle media button events
    intent?.let {
      MediaButtonReceiver.handleIntent(mediaSession, it)
      
      // Get media info from intent extras if available
      val title = it.getStringExtra("media_title")
      val artist = it.getStringExtra("media_artist")
      
      if (!title.isNullOrBlank()) {
        mediaTitle = title
        mediaArtist = artist ?: ""
        Log.d(TAG, "Media info from intent: $mediaTitle")
      }
    }

    updateMediaSession()

    // Always start as foreground service with notification (like YouTube)
    try {
      val type =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
          0
        }
      ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
      Log.d(TAG, "Foreground service started successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Error starting foreground service", e)
    }

    // Return START_NOT_STICKY so service doesn't restart if killed
    return START_NOT_STICKY
  }

  override fun onGetRoot(
    clientPackageName: String,
    clientUid: Int,
    rootHints: android.os.Bundle?,
  ) = BrowserRoot("root_id", null)

  override fun onLoadChildren(
    parentId: String,
    result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
  ) {
    result.sendResult(mutableListOf())
  }

  fun setMediaInfo(
    title: String,
    artist: String,
    thumbnail: Bitmap? = null,
  ) {
    MediaPlaybackService.thumbnail = thumbnail
    mediaTitle = title
    mediaArtist = artist
    updateMediaSession()
  }

  private fun setupMediaSession() {
    mediaSession =
      MediaSessionCompat(this, TAG).apply {
        setCallback(
          object : MediaSessionCompat.Callback() {
            override fun onPlay() {
              Log.d(TAG, "onPlay called")
              controls?.setPaused(false)
            }

            override fun onPause() {
              Log.d(TAG, "onPause called")
              controls?.setPaused(true)
            }

            override fun onStop() {
              Log.d(TAG, "onStop called")
              controls?.stop()
              stopSelf()
            }

            override fun onSkipToNext() {
              Log.d(TAG, "onSkipToNext called")
              controls?.seekBy(10)
            }

            override fun onSkipToPrevious() {
              controls?.seekBy(-10)
            }

            override fun onSeekTo(pos: Long) {
              Log.d(TAG, "onSeekTo called: $pos")
              controls?.seekTo((pos / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            }
          },
        )

        // Set flags to handle media buttons and transport controls
        @Suppress("DEPRECATION")
        setFlags(
          MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )

        isActive = true
      }
    sessionToken = mediaSession.sessionToken
  }

  private fun updateMediaSession() {
    try {
      // Ensure we have valid media title
      val title = mediaTitle.ifBlank { "Unknown Video" }
      
      // Update metadata
      val duration = ((playback.duration ?: 0f) * 1000).toLong()

      val metadataBuilder =
        MediaMetadataCompat
          .Builder()
          .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
          .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mediaArtist)
          .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
          .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

      thumbnail?.let {
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
      }
      mediaSession.setMetadata(metadataBuilder.build())

      // Update playback state
      val position = (playback.position * 1000).toLong()
      val state = when (playback.phase) {
        PlaybackPhase.Preparing, PlaybackPhase.Opening -> PlaybackStateCompat.STATE_BUFFERING
        PlaybackPhase.Failed -> PlaybackStateCompat.STATE_ERROR
        PlaybackPhase.Idle, PlaybackPhase.Stopping -> PlaybackStateCompat.STATE_STOPPED
        PlaybackPhase.Ready -> if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
      }

      mediaSession.setPlaybackState(
        PlaybackStateCompat
          .Builder()
          .setActions(
            PlaybackStateCompat.ACTION_PLAY or
              PlaybackStateCompat.ACTION_PAUSE or
              PlaybackStateCompat.ACTION_PLAY_PAUSE or
              PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
              PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
              PlaybackStateCompat.ACTION_STOP or
              PlaybackStateCompat.ACTION_SEEK_TO,
          ).setState(state, position, 1.0f)
          .build(),
      )

      // Update notification
      updateNotification()
    } catch (e: Exception) {
      Log.e(TAG, "Error updating MediaSession", e)
    }
  }

  private fun updateNotification() {
    try {
      val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.notify(NOTIFICATION_ID, buildNotification())
    } catch (e: Exception) {
      Log.e(TAG, "Error updating notification", e)
    }
  }

  private fun buildNotification(): Notification {
    val openAppIntent =
      Intent(this, PlayerActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        openAppIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    // Create notification actions
    val previousAction =
      NotificationCompat.Action(
        android.R.drawable.ic_media_previous,
        "Previous",
        MediaButtonReceiver.buildMediaButtonPendingIntent(
          this,
          PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
        ),
      )

    val playPauseAction =
      NotificationCompat.Action(
        if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
        if (paused) "Play" else "Pause",
        MediaButtonReceiver.buildMediaButtonPendingIntent(
          this,
          PlaybackStateCompat.ACTION_PLAY_PAUSE,
        ),
      )

    val nextAction =
      NotificationCompat.Action(
        android.R.drawable.ic_media_next,
        "Next",
        MediaButtonReceiver.buildMediaButtonPendingIntent(
          this,
          PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
        ),
      )

    return NotificationCompat
      .Builder(this, NOTIFICATION_CHANNEL_ID)
      .setContentTitle(mediaTitle.ifBlank { "Unknown Video" })
      .setContentText(mediaArtist.ifBlank { getString(R.string.notification_playing) })
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setLargeIcon(thumbnail)
      .setContentIntent(pendingIntent)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setOngoing(!paused)
      .addAction(previousAction)
      .addAction(playPauseAction)
      .addAction(nextAction)
      .setStyle(
        androidx.media.app.NotificationCompat
          .MediaStyle()
          .setMediaSession(mediaSession.sessionToken)
          .setShowActionsInCompactView(0, 1, 2),
      ).setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  override fun onDestroy() {
    try {
      Log.d(TAG, "Service destroyed")

      isServiceRunning = false
      
      attachControls(null)
      serviceScope.cancel()

      // Stop foreground and remove notification explicitly
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
          @Suppress("DEPRECATION")
          stopForeground(true)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping foreground", e)
      }
      
      // Cancel notification explicitly to ensure cleanup
      try {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
      } catch (e: Exception) {
        Log.e(TAG, "Error canceling notification", e)
      }
      
      // Release media session
      try {
        mediaSession.isActive = false
        mediaSession.release()
      } catch (e: Exception) {
        Log.e(TAG, "Error releasing media session", e)
      }
      
      // Clear thumbnail to prevent memory leak
      thumbnail = null
      
      Log.d(TAG, "Service cleanup completed")
      super.onDestroy()
    } catch (e: Exception) {
      Log.e(TAG, "Error in onDestroy", e)
      super.onDestroy()
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    Log.d(TAG, "Task removed - killing playback and cleaning up service")
    try {
      // Kill MPV playback immediately when task is removed
      try {
        MpvGateway.command("quit")
        Log.d(TAG, "MPV quit command sent")
      } catch (e: Exception) {
        Log.e(TAG, "Error sending quit command to MPV", e)
      }
      
      // Stop foreground and remove notification when task is removed
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
          @Suppress("DEPRECATION")
          stopForeground(true)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error stopping foreground in onTaskRemoved", e)
      }
      
      // Cancel notification explicitly
      try {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
      } catch (e: Exception) {
        Log.e(TAG, "Error canceling notification in onTaskRemoved", e)
      }
      
      // Clear thumbnail
      thumbnail = null
      
      // Stop the service which will trigger cleanup
      stopSelf()
      
      // Force kill the process to ensure everything stops
      android.os.Process.killProcess(android.os.Process.myPid())
    } catch (e: Exception) {
      Log.e(TAG, "Error in onTaskRemoved", e)
      // Force kill even if there's an error
      android.os.Process.killProcess(android.os.Process.myPid())
    }
    super.onTaskRemoved(rootIntent)
  }
}
