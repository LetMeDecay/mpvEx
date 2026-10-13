package app.marlboroadvance.mpvex.ui.player

import android.net.Uri
import java.util.UUID

/** Single-use queue handoff keeps large URI arrays out of Binder transactions. */
internal object PlaybackQueueRegistry {
  private data class Entry(val items: List<Uri>, val createdAt: Long, val onDrop: () -> Unit)
  private val entries = linkedMapOf<String, Entry>()
  const val EXTRA = "playback_queue_id"

  @Synchronized
  fun put(items: List<Uri>, onDrop: () -> Unit = {}): String {
    val now = android.os.SystemClock.elapsedRealtime()
    entries.entries.filter { now - it.value.createdAt > 300_000L }.map { it.key }.forEach(::discard)
    while (entries.size >= 4) discard(entries.keys.first())
    return UUID.randomUUID().toString().also { entries[it] = Entry(items.toList(), now, onDrop) }
  }

  @Synchronized
  fun take(id: String): List<Uri>? = entries.remove(id)?.items

  @Synchronized
  fun discard(id: String?) {
    entries.remove(id)?.let { runCatching(it.onDrop) }
  }
}
