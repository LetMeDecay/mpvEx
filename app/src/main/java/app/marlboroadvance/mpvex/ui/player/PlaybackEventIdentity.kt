package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.CompletableDeferred

/** Associates the native event stream with the ID returned by its load command. */
internal class PlaybackEventIdentity {
  class Request(val generation: Long) {
    internal val entry = CompletableDeferred<Long?>()
  }
  data class Event internal constructor(
    internal val request: Request,
    val entryId: Long,
    internal val rootId: Long,
  ) {
    val generation get() = request.generation
  }
  private data class Redirect(val first: Long, val count: Long, val root: Long)
  private var request: Request? = null
  private var activeEntry: Long? = null
  private var lastEndedEntry: Long? = null
  private val redirects = mutableListOf<Redirect>()

  @Synchronized fun begin(generation: Long): Request {
    request?.entry?.complete(null)
    redirects.clear()
    lastEndedEntry = null
    return Request(generation).also { request = it }
  }

  fun bind(request: Request, entryId: Long?) {
    request.entry.complete(entryId)
  }

  @Synchronized fun start(entryId: Long?) {
    activeEntry = entryId
    lastEndedEntry = null
  }

  @Synchronized fun end(entryId: Long?, insertId: Long?, insertCount: Long) {
    if (entryId == null) return
    // Store ranges rather than allocating one entry per item in a remote playlist.
    if (insertId != null && insertId >= 0 && insertCount > 0) {
      redirects.add(Redirect(insertId, insertCount, rootOf(entryId)))
    }
    if (activeEntry == entryId) {
      activeEntry = null
      lastEndedEntry = entryId
    }
  }

  private fun rootOf(id: Long): Long =
    redirects.lastOrNull { id >= it.first && id - it.first < it.count }?.root ?: id

  @Synchronized fun capture(): Event? {
    val current = request ?: return null
    val entry = activeEntry ?: return null
    return Event(current, entry, rootOf(entry))
  }

  suspend fun resolve(event: Event): Boolean =
    event.request.entry.await() == event.rootId && isCurrent(event)

  suspend fun resolveEnded(event: Event): Boolean {
    if (event.request.entry.await() != event.rootId) return false
    return synchronized(this) {
      request === event.request && activeEntry == null && lastEndedEntry == event.entryId
    }
  }

  @Synchronized fun isCurrent(event: Event): Boolean =
    request === event.request && activeEntry == event.entryId

  @Synchronized fun retire() {
    request?.entry?.complete(null)
    request = null
    activeEntry = null
    lastEndedEntry = null
    redirects.clear()
  }
}
