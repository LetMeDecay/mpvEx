package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import java.util.PriorityQueue

/**
 * Pure deduplicating priority queue used by the single native thumbnail
 * consumer. Lower [priorityRank] values are dispatched first; offering an
 * existing key promotes it in place without creating another entry.
 */
internal class ThumbnailPriorityQueue<K, P>(
  private val priorityRank: (P) -> Int,
) {
  data class Entry<K, P>(
    val key: K,
    var priority: P,
    val sequence: Long,
  )

  private val queue = PriorityQueue<Entry<K, P>>(
    compareBy<Entry<K, P>> { priorityRank(it.priority) }.thenBy { it.sequence },
  )
  private val queuedByKey = mutableMapOf<K, Entry<K, P>>()
  private var nextSequence = 0L

  @Synchronized
  fun offer(key: K, priority: P): Entry<K, P> {
    val existing = queuedByKey[key]
    if (existing != null) {
      promote(key, priority)
      return existing
    }
    return Entry(key, priority, nextSequence++).also {
      queuedByKey[key] = it
      queue.add(it)
    }
  }

  @Synchronized
  fun promote(key: K, priority: P): Boolean {
    val existing = queuedByKey[key] ?: return false
    if (priorityRank(priority) >= priorityRank(existing.priority)) return false
    queue.remove(existing)
    existing.priority = priority
    queue.add(existing)
    return true
  }

  @Synchronized
  fun poll(): Entry<K, P>? = queue.poll()?.also { queuedByKey.remove(it.key, it) }

  @Synchronized
  fun cancel(key: K): Boolean {
    val existing = queuedByKey.remove(key) ?: return false
    queue.remove(existing)
    return true
  }

  @Synchronized
  fun entries(): List<Entry<K, P>> = queuedByKey.values.toList()

  @Synchronized
  fun size(): Int = queue.size
}
