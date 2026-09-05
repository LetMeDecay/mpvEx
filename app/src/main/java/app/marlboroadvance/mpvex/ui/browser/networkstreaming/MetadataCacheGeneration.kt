package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Makes invalidation and result publication atomic, without blocking network work. */
internal class MetadataCacheGeneration {
  private var allGeneration = 0L
  private val connections = mutableMapOf<String, Long>()
  private val files = mutableMapOf<String, Long>()
  private val _changes = MutableStateFlow(0L)
  val changes = _changes.asStateFlow()

  @Synchronized fun token(key: String): Long =
    maxOf(allGeneration, connections[key.substringBefore("::")] ?: 0L, files[key] ?: 0L)

  @Synchronized fun <T> read(block: () -> T): T = block()

  @Synchronized fun publish(key: String, token: Long, block: () -> Unit): Boolean {
    if (token(key) != token) return false
    block()
    return true
  }

  @Synchronized fun invalidate(key: String, clear: () -> Unit) {
    val next = _changes.value + 1
    files[key] = next
    try { clear() } finally { _changes.value = next }
  }

  @Synchronized fun invalidateConnection(connectionId: Long, clear: () -> Unit) {
    val next = _changes.value + 1
    connections[connectionId.toString()] = next
    files.keys.removeAll { it.startsWith("$connectionId::") }
    try { clear() } finally { _changes.value = next }
  }

  @Synchronized fun clear(clear: () -> Unit) {
    val next = _changes.value + 1
    allGeneration = next
    connections.clear()
    files.clear()
    try { clear() } finally { _changes.value = next }
  }
}
