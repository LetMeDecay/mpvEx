package app.marlboroadvance.mpvex.utils.media

import java.util.concurrent.ConcurrentHashMap

/** Tracks only currently running prefetches; completed keys may run again. */
class InFlightPrefetchTracker {
  private val keys = ConcurrentHashMap.newKeySet<String>()

  fun tryStart(key: String): Boolean = keys.add(key)

  fun complete(key: String) {
    keys.remove(key)
  }
}

/** Pure playlist-order logic used to choose the item to prefetch. */
object PlaylistPrefetchOrder {
  fun nextIndex(
    currentIndex: Int,
    playlistSize: Int,
    shuffleEnabled: Boolean,
    shuffledIndices: List<Int>,
    repeatAll: Boolean,
  ): Int? {
    if (playlistSize <= 1 || currentIndex !in 0 until playlistSize) return null

    if (shuffleEnabled) {
      val position = shuffledIndices.indexOf(currentIndex)
      return if (position >= 0 && position < shuffledIndices.lastIndex) {
        shuffledIndices[position + 1]
      } else {
        // The next repeat cycle is regenerated randomly, so its first item is
        // not known until playback advances.
        null
      }
    }

    val next = currentIndex + 1
    return when {
      next < playlistSize -> next
      repeatAll -> 0
      else -> null
    }
  }
}
