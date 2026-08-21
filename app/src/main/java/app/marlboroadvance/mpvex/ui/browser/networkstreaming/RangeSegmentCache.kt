package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/**
 * A bounded LRU for exact byte ranges. The cache is independent of Android
 * and network clients so its identity and memory invariants can be tested
 * without opening a server or loading the native player library.
 */
internal class RangeSegmentCache(
  private val maxSegmentBytes: Int = MAX_SEGMENT_BYTES,
  private val maxEntries: Int = MAX_ENTRIES,
  private val maxTotalBytes: Int = MAX_TOTAL_BYTES,
) {
  companion object {
    const val MAX_SEGMENT_BYTES = 2 * 1024 * 1024
    const val MAX_ENTRIES = 16
    const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
  }

  private data class SegmentKey(
    val identity: String,
    val start: Long,
    val end: Long,
  )

  private val cache = LinkedHashMap<SegmentKey, ByteArray>(maxEntries, 0.75f, true)
  private var totalBytes = 0

  @Synchronized
  fun get(identity: String, start: Long, end: Long): ByteArray? {
    if (start < 0 || end < start) return null
    val containing = cache.keys.firstOrNull { key ->
      key.identity == identity && start >= key.start && end <= key.end
    } ?: return null
    val bytes = cache[containing] ?: return null // also updates LRU order
    val from = (start - containing.start).toInt()
    val length = (end - start + 1).toInt()
    return bytes.copyOfRange(from, from + length)
  }

  @Synchronized
  fun put(identity: String, start: Long, end: Long, bytes: ByteArray) {
    val expectedLength = end - start + 1
    if (
      start < 0 ||
      end < start ||
      expectedLength <= 0 ||
      expectedLength > maxSegmentBytes ||
      bytes.size.toLong() != expectedLength
    ) {
      return
    }

    val key = SegmentKey(identity, start, end)
    cache.remove(key)?.let { totalBytes -= it.size }
    // Keep callers from mutating a segment after it has entered the cache.
    cache[key] = bytes.copyOf()
    totalBytes += bytes.size
    while (cache.size > maxEntries || totalBytes > maxTotalBytes) {
      val eldest = cache.entries.iterator().next()
      totalBytes -= eldest.value.size
      cache.remove(eldest.key)
    }
  }

  @Synchronized
  fun entryCount(): Int = cache.size

  @Synchronized
  fun totalBytes(): Int = totalBytes
}
