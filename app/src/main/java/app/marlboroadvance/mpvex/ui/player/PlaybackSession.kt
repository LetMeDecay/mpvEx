package app.marlboroadvance.mpvex.ui.player

/** File and user-interaction identity shared by delayed playback operations. */
internal class PlaybackSession {
  data class Ticket(val generation: Long, val interaction: Long)

  @Volatile var generation = 0L
    private set
  @Volatile var loaded = false
    private set
  @Volatile private var expectedPath: String? = null
  @Volatile private var interaction = 0L
  private var pendingPosition: Float? = null
  private var pendingSince = 0L

  fun begin(path: String) {
    loaded = false
    pendingPosition = null
    generation++
    interaction++
    expectedPath = path.removePrefix("file://")
  }

  fun failed(eventGeneration: Long) {
    if (generation != eventGeneration) return
    loaded = false
    interact()
  }

  fun retire() {
    loaded = false
    expectedPath = null
    generation++
    interact()
  }

  /** Only called after native entry identity has been verified. */
  fun adoptNativePath(path: String, eventGeneration: Long) {
    if (generation != eventGeneration) return
    val normalized = path.removePrefix("file://")
    if (expectedPath != normalized) {
      expectedPath = normalized
      loaded = false
      interact()
    }
  }

  fun acceptsPath(path: String?): Boolean =
    path != null && path.removePrefix("file://") == expectedPath

  fun acceptsEvent(path: String?, eventGeneration: Long): Boolean =
    eventGeneration == generation && acceptsPath(path)

  fun fileLoaded(path: String?): Boolean {
    if (!acceptsPath(path)) return false
    loaded = true
    return true
  }

  /** FILE_LOADED and PLAYBACK_RESTART may both confirm the same load. */
  fun confirmLoaded(path: String?, eventGeneration: Long, currentPath: String?): Boolean {
    if (loaded || !acceptsEvent(path, eventGeneration) || !acceptsPath(currentPath)) return false
    return fileLoaded(path)
  }

  fun interact() {
    interaction++
    pendingPosition = null
  }

  fun seek(position: Float, now: Long, showTarget: Boolean = true): Ticket {
    interact()
    pendingPosition = position.takeIf { showTarget }
    pendingSince = now
    return ticket()
  }

  fun displayPosition(actual: Float?, seeking: Boolean, now: Long): Float {
    if (!loaded) return 0f
    val current = actual?.takeIf { it.isFinite() && it >= 0f } ?: 0f
    pendingPosition?.let { target ->
      // Do not bounce to pre-seek samples while the network seek is settling.
      // If mpv rejects a seek, stop showing its target after a bounded wait.
      if ((!seeking && actual != null && kotlin.math.abs(current - target) < 1f) ||
        now - pendingSince >= 10_000L) pendingPosition = null
    }
    return pendingPosition ?: current
  }
  fun ticket() = Ticket(generation, interaction)
  fun isCurrent(ticket: Ticket): Boolean =
    loaded && ticket.generation == generation && ticket.interaction == interaction
}
