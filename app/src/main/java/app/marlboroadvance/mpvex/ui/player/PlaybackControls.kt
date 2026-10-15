package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.flow.StateFlow

internal val LocalPlaybackControls = androidx.compose.runtime.staticCompositionLocalOf<PlaybackControls> {
  error("Playback controls are not attached")
}

/** Typed controls shared by player panels, notification and picture-in-picture. */
internal interface PlaybackControls {
  val state: StateFlow<PlaybackUiState>
  fun setPaused(paused: Boolean)
  fun seekBy(seconds: Int)
  fun seekTo(seconds: Int)
  fun setSpeed(value: Float)
  fun selectAudio(id: Int?)
  fun toggleAudio(id: Int)
  fun setChapter(index: Int)
  fun setDecoder(value: Decoder)
  fun setPitchCorrection(enabled: Boolean)
  fun setAudioDelay(seconds: Double)
  fun setSubtitleDelay(seconds: Double)
  fun setSubtitleSpeed(value: Double)
  fun <T> setTuning(setting: PlaybackTuning<T>, value: T)
  fun setVideoFilter(filter: VideoFilters, value: Int)
  fun setDebandSetting(setting: DebandSettings, value: Int)
  fun setDebanding(mode: Debanding)
  fun setAudioChannels(channels: app.marlboroadvance.mpvex.preferences.AudioChannels)
  suspend fun stepFrame(backwards: Boolean, generation: Long): Boolean
  suspend fun captureFrame(path: String, includeSubtitles: Boolean, generation: Long): Boolean
  fun stop()
}
