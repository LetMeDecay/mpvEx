package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/** Tracks only streams adopted by the player, never another pending launch or metadata probe. */
internal class PlaybackStreamOwnership {
  private val owned = mutableSetOf<String>()
  private var queue = emptySet<String>()

  @Synchronized fun replaceQueue(streams: Set<String>) {
    owned.addAll(streams)
    queue = streams.toSet()
  }

  @Synchronized fun remember(stream: String) { owned.add(stream) }

  /** Call only after mpv confirms the loaded file, retaining its stream during transitions. */
  @Synchronized fun releaseUnused(current: String?): Set<String> {
    val obsolete = owned.filterTo(mutableSetOf()) { it !in queue && it != current }
    owned.removeAll(obsolete)
    return obsolete
  }

  /** Call only after the player has actually stopped, not on Activity recreation/PiP. */
  @Synchronized fun releaseAll(): Set<String> = owned.toSet().also {
    owned.clear()
    queue = emptySet()
  }
}
