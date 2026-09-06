package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cumulative scopes survive StateFlow conflation; no invalidated path is silently dropped. */
internal data class CacheInvalidationSnapshot(
  val revision: Long = 0L,
  val all: Long = 0L,
  val connections: Map<Long, Long> = emptyMap(),
  val paths: Map<Long, Map<String, Long>> = emptyMap(),
) {
  /** null means the whole connection; an empty set means this browser is unaffected. */
  fun pathsSince(previous: Long, connectionId: Long): Set<String>? {
    if (all > previous || (connections[connectionId] ?: 0L) > previous) return null
    return paths[connectionId].orEmpty().filterValues { it > previous }.keys
  }
}

/** Only short in-memory operations belong in these callbacks; disk work uses ThumbnailDiskStore. */
internal class MetadataCacheGeneration {
  private var allGeneration = 0L
  private val connections = mutableMapOf<Long, Long>()
  private val files = mutableMapOf<String, Long>()
  private val _changes = MutableStateFlow(CacheInvalidationSnapshot())
  val changes = _changes.asStateFlow()

  @Synchronized fun token(key: String): Long =
    maxOf(allGeneration, connections[key.substringBefore("::").toLongOrNull()] ?: 0L, files[key] ?: 0L)

  @Synchronized fun <T> read(block: () -> T): T = block()

  @Synchronized fun publish(key: String, token: Long, block: () -> Unit): Boolean {
    if (token(key) != token) return false
    block()
    return true
  }

  @Synchronized fun invalidate(connectionId: Long, keysToPaths: Map<String, String>, clear: () -> Unit) {
    if (keysToPaths.isEmpty()) return
    val previous = _changes.value
    val next = previous.revision + 1
    keysToPaths.keys.forEach { files[it] = next }
    val paths = previous.paths[connectionId].orEmpty() + keysToPaths.values.associateWith { next }
    try { clear() } finally {
      _changes.value = previous.copy(revision = next, paths = previous.paths + (connectionId to paths))
    }
  }

  @Synchronized fun invalidateConnection(connectionId: Long, clear: () -> Unit) {
    val previous = _changes.value
    val next = previous.revision + 1
    connections[connectionId] = next
    files.keys.removeAll { it.startsWith("$connectionId::") }
    try { clear() } finally {
      _changes.value = previous.copy(
        revision = next,
        connections = previous.connections + (connectionId to next),
        paths = previous.paths - connectionId,
      )
    }
  }

  @Synchronized fun clear(clear: () -> Unit) {
    val next = _changes.value.revision + 1
    allGeneration = next
    connections.clear()
    files.clear()
    try { clear() } finally { _changes.value = CacheInvalidationSnapshot(revision = next, all = next) }
  }
}
