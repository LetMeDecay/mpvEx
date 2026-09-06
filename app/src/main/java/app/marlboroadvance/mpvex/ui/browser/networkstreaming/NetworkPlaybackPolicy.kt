package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Network playback owns bandwidth ahead of new metadata work; cache hits bypass this gate. */
internal class NetworkPlaybackPolicy {
  companion object {
    val shared = NetworkPlaybackPolicy()
  }

  private val playing = MutableStateFlow<String?>(null)

  fun setPlayback(streamId: String?) { playing.value = streamId }

  fun retire(streamIds: Collection<String>) {
    val current = playing.value
    if (current != null && current in streamIds) playing.compareAndSet(current, null)
  }

  suspend fun awaitMetadata() { playing.first { it == null } }

  fun canPrefetch(remaining: Double, cachedSeconds: Double, seeking: Boolean, buffering: Boolean): Boolean =
    playing.value != null && remaining.isFinite() && remaining > 0 && remaining <= 20 &&
      cachedSeconds.isFinite() && cachedSeconds >= minOf(10.0, remaining) && !seeking && !buffering
}
