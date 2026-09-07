package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Network playback owns bandwidth ahead of new metadata work; cache hits bypass this gate. */
internal class NetworkPlaybackPolicy {
  companion object {
    val shared = NetworkPlaybackPolicy()
  }

  private val playing = MutableStateFlow<String?>(null)

  private var selected: String? = null

  @Synchronized
  fun setPlayback(streamId: String?) {
    selected = streamId
    playing.value = streamId
  }

  /** EOF releases bandwidth while retaining the identity needed for replay. */
  @Synchronized
  fun setEnded(streamId: String, ended: Boolean) {
    if (selected == streamId) playing.value = if (ended) null else streamId
  }

  @Synchronized
  fun retire(streamIds: Collection<String>) {
    if (selected in streamIds) {
      selected = null
      playing.value = null
    }
  }

  suspend fun awaitMetadata() { playing.first { it == null } }

  fun canPrefetch(remaining: Double, cachedSeconds: Double, seeking: Boolean, buffering: Boolean): Boolean =
    playing.value != null && remaining.isFinite() && remaining > 0 && remaining <= 20 &&
      cachedSeconds.isFinite() && cachedSeconds >= minOf(10.0, remaining) && !seeking && !buffering
}
