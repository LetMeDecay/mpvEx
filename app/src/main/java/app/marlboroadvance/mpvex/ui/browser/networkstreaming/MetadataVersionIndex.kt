package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/** Unknown legacy path boundaries are never guessed for lookup or per-file deletion. */
internal class MetadataVersionIndex {
  private val byPath = mutableMapOf<Pair<Long, String>, MutableSet<String>>()
  private val owners = mutableMapOf<String, Pair<Long, String>>()
  private val unclassified = mutableSetOf<String>()

  fun add(key: String) {
    if (key in owners) return
    val parts = key.split("::")
    val connectionId = parts.firstOrNull()?.toLongOrNull()
    if (parts.size == 5 && connectionId != null &&
      parts[2].toLongOrNull() != null && parts[4].toLongOrNull() != null) {
      add(key, connectionId, parts[1])
    } else {
      unclassified.add(key)
    }
  }

  /** Exact identity is available from live directory listings/probe requests. */
  fun add(key: String, connectionId: Long, path: String) {
    remove(key)
    val owner = connectionId to path
    owners[key] = owner
    byPath.getOrPut(owner) { mutableSetOf() }.add(key)
  }

  fun keysFor(connectionId: Long, path: String): Set<String> =
    byPath[connectionId to path]?.toSet().orEmpty()

  fun keysForConnection(connectionId: Long): Set<String> =
    byPath.filterKeys { it.first == connectionId }.values.flatten().toSet() +
      unclassified.filter { it.substringBefore("::").toLongOrNull() == connectionId }

  fun remove(key: String) {
    unclassified.remove(key)
    owners.remove(key)?.let { owner ->
      byPath[owner]?.let { keys ->
        keys.remove(key)
        if (keys.isEmpty()) byPath.remove(owner)
      }
    }
  }

  fun clear() { byPath.clear(); owners.clear(); unclassified.clear() }
}
