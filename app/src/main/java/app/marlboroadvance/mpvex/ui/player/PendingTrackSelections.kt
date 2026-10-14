package app.marlboroadvance.mpvex.ui.player

internal enum class PlaybackTrack(val property: String) {
  Audio("aid"), Subtitle("sid"), SecondarySubtitle("secondary-sid"),
}

/** Rapid input uses the last intent until native confirms it or the bound expires. */
internal class PendingTrackSelections {
  private data class Choice(val id: Int, val requestedAt: Long)
  private val pending = mutableMapOf<PlaybackTrack, Choice>()

  fun choose(track: PlaybackTrack, id: Int, now: Long) {
    pending[track] = Choice(id, now)
  }

  fun selected(track: PlaybackTrack, actual: Int, now: Long): Int {
    val choice = pending[track] ?: return actual
    if (now - choice.requestedAt < 10_000) return choice.id
    pending.remove(track)
    return actual
  }

  fun acknowledge(track: PlaybackTrack, actual: Int) {
    if (pending[track]?.id == actual) pending.remove(track)
  }

  fun clear() = pending.clear()
}
