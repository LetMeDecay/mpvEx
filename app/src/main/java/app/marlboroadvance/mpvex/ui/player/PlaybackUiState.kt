package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class PlaybackPhase { Idle, Preparing, Opening, Ready, Stopping, Failed }

internal data class PlaybackUiState(
  val generation: Long = 0,
  val phase: PlaybackPhase = PlaybackPhase.Idle,
  val position: Float = 0f,
  val duration: Float? = null,
  val paused: Boolean = true,
  val seeking: Boolean = false,
  val buffering: Boolean = false,
)

/** One atomic timeline snapshot; late IO cannot publish into a replacement file. */
internal class PlaybackStateStore {
  private val mutable = MutableStateFlow(PlaybackUiState())
  val state = mutable.asStateFlow()

  fun begin(generation: Long) {
    mutable.value = PlaybackUiState(generation, PlaybackPhase.Preparing)
  }

  fun opening(generation: Long) = mutable.update {
    if (it.generation == generation && it.phase == PlaybackPhase.Preparing) {
      it.copy(phase = PlaybackPhase.Opening)
    } else it
  }

  fun ready(generation: Long) = mutable.update {
    if (it.generation == generation && it.phase != PlaybackPhase.Stopping && it.phase != PlaybackPhase.Idle) {
      PlaybackUiState(generation, PlaybackPhase.Ready)
    } else it
  }

  fun sample(generation: Long, position: Float, duration: Float?, paused: Boolean,
             seeking: Boolean, buffering: Boolean) = mutable.update {
    if (it.generation != generation || it.phase !in activePhases) it else it.copy(
      phase = PlaybackPhase.Ready,
      position = position.takeIf { value -> value.isFinite() && value >= 0f } ?: 0f,
      duration = duration?.takeIf { value -> value.isFinite() && value > 0f },
      paused = paused,
      seeking = seeking,
      buffering = buffering,
    )
  }

  fun seek(generation: Long, position: Float) = mutable.update {
    if (it.generation == generation && it.phase == PlaybackPhase.Ready) {
      it.copy(position = position, seeking = true)
    } else it
  }

  fun failed(generation: Long) = mutable.update {
    if (it.generation == generation && it.phase in activePhases) {
      it.copy(phase = PlaybackPhase.Failed)
    } else it
  }

  fun stopping(generation: Long) {
    mutable.value = PlaybackUiState(generation, PlaybackPhase.Stopping)
  }

  fun stopped(generation: Long) = mutable.update {
    if (it.generation == generation && it.phase == PlaybackPhase.Stopping) {
      PlaybackUiState(generation)
    } else it
  }

  private companion object {
    val activePhases = setOf(PlaybackPhase.Preparing, PlaybackPhase.Opening, PlaybackPhase.Ready)
  }
}
