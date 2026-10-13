package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.flow.StateFlow

/** Transport shared by the player, notification and picture-in-picture. */
internal interface PlaybackControls {
  val state: StateFlow<PlaybackUiState>
  fun setPaused(paused: Boolean)
  fun seekBy(seconds: Int)
  fun seekTo(seconds: Int)
  fun stop()
}
