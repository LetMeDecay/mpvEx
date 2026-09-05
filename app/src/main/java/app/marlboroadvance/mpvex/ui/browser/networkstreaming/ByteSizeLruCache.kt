package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/** An access-ordered cache whose retained values have an explicit byte budget. */
internal class ByteSizeLruCache<K, V>(
  private val maxBytes: Long,
  private val sizeOf: (V) -> Long,
) {
  private data class Entry<V>(val value: V, val bytes: Long)
  private val values = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
  private var bytes = 0L
  init { require(maxBytes >= 0) }

  @Synchronized operator fun get(key: K): V? = values[key]?.value
  @Synchronized fun containsKey(key: K): Boolean = values.containsKey(key)
  val keys: Set<K> @Synchronized get() = values.keys.toSet()

  @Synchronized operator fun set(key: K, value: V) {
    val size = sizeOf(value)
    require(size >= 0)
    remove(key)
    if (size > maxBytes) return
    values[key] = Entry(value, size)
    bytes += size
    val iterator = values.entries.iterator()
    while (bytes > maxBytes && iterator.hasNext()) {
      bytes -= iterator.next().value.bytes
      iterator.remove()
    }
  }

  @Synchronized fun remove(key: K): V? = values.remove(key)?.let {
    bytes -= it.bytes
    it.value
  }

  @Synchronized fun clear() { values.clear(); bytes = 0L }
  @Synchronized fun totalBytes(): Long = bytes
}
