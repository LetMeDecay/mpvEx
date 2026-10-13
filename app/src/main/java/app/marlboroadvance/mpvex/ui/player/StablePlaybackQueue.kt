package app.marlboroadvance.mpvex.ui.player

/** Queue identity is fixed at open; reordering the database cannot change an index. */
internal class StablePlaybackQueue<K>(keys: List<K>) {
  private val keys = keys.toList()
  val size get() = keys.size
  data class Window<V>(val offset: Int, val items: List<V>)

  suspend fun <V> item(index: Int, read: suspend (List<K>) -> Map<K, V>): V {
    require(index in keys.indices)
    val key = keys[index]
    return checkNotNull(read(listOf(key))[key]) { "Queue item is no longer available" }
  }

  suspend fun <V> window(center: Int, limit: Int, read: suspend (List<K>) -> Map<K, V>): Window<V> {
    require(limit > 0)
    if (keys.isEmpty()) return Window(0, emptyList())
    require(center in keys.indices)
    val start = (center - limit / 2).coerceIn(0, (size - limit).coerceAtLeast(0))
    val requested = keys.subList(start, (start + limit).coerceAtMost(size))
    val found = read(requested)
    return Window(start, requested.map { checkNotNull(found[it]) { "Queue changed; reopen the playlist" } })
  }
}
