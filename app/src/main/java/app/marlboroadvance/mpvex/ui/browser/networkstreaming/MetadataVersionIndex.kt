package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/** Indexes canonical cache keys once; unusual legacy delimiters keep a conservative fallback. */
internal class MetadataVersionIndex {
  private val byPath = mutableMapOf<String, MutableSet<String>>()
  private val ambiguous = mutableSetOf<String>()

  private fun pathKey(key: String): String? {
    val parts = key.split("::")
    if (parts.size != 5 || parts[0].toLongOrNull() == null ||
      parts[2].toLongOrNull() == null || parts[4].toLongOrNull() == null) return null
    return "${parts[0]}::${parts[1]}"
  }

  fun add(key: String) {
    val path = pathKey(key)
    if (path == null) ambiguous.add(key)
    else byPath.getOrPut(path) { mutableSetOf() }.add(key)
  }

  fun keysFor(connectionId: Long, path: String): Set<String> {
    val lookup = "$connectionId::$path"
    return byPath[lookup].orEmpty() + ambiguous.filter { it.startsWith("$lookup::") }
  }

  fun keysForConnection(connectionId: Long): Set<String> {
    val prefix = "$connectionId::"
    return byPath.filterKeys { it.startsWith(prefix) }.values.flatten().toSet() +
      ambiguous.filter { it.startsWith(prefix) }
  }

  fun remove(key: String) {
    val path = pathKey(key)
    if (path == null) ambiguous.remove(key)
    else byPath[path]?.let { keys ->
      keys.remove(key)
      if (keys.isEmpty()) byPath.remove(path)
    }
  }

  fun clear() { byPath.clear(); ambiguous.clear() }
}
