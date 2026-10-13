package app.marlboroadvance.mpvex.ui.player

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** fd:// borrows its descriptor; the application keeps it open until END_FILE. */
internal class PlayableSource(
  val path: String,
  private val release: () -> Unit = {},
) : Closeable {
  private val closed = AtomicBoolean()
  override fun close() {
    if (closed.compareAndSet(false, true)) runCatching(release)
  }
}

/** Resource ownership follows native entries, including END_FILE before command reply. */
internal class PlaybackSourceLeases {
  private data class Entry(val source: PlayableSource, val order: Long)
  private val lock = Any()
  private val loadLock = Any()
  private val entries = mutableMapOf<Long, Entry>()
  private val extras = mutableListOf<PlayableSource>()
  private var submitting = false
  private var order = 0L
  private val earlyEnds = mutableSetOf<Long>()
  private val earlyStarts = mutableSetOf<Long>()

  fun load(source: PlayableSource, command: (String) -> Long): Long = synchronized(loadLock) {
    val submittedOrder = synchronized(lock) {
      submitting = true
      earlyEnds.clear()
      earlyStarts.clear()
      ++order
    }
    var retained = false
    try {
      val id = command(source.path)
      val obsolete = synchronized(lock) {
        val old = if (id in earlyStarts) retireBefore(submittedOrder) else emptyList()
        if (id !in earlyEnds) {
          check(id !in entries)
          entries[id] = Entry(source, submittedOrder)
          retained = true
        }
        old
      }
      obsolete.forEach { it.close() }
      id
    } finally {
      synchronized(lock) {
        submitting = false
        earlyEnds.clear()
        earlyStarts.clear()
      }
      if (!retained) source.close()
    }
  }

  // A replacement may skip an entry entirely, emitting neither START nor END for it.
  // Once a later submitted entry starts, earlier resources can no longer be read.
  private fun retireBefore(order: Long): List<PlayableSource> {
    val obsolete = entries.filterValues { it.order < order }
    obsolete.keys.forEach { entries.remove(it) }
    return obsolete.values.map { it.source }
  }

  fun started(id: Long) {
    val obsolete = synchronized(lock) {
      val entry = entries[id]
      if (entry != null) retireBefore(entry.order) else {
        if (submitting) earlyStarts.add(id)
        emptyList()
      }
    }
    obsolete.forEach { it.close() }
  }

  fun ended(id: Long) {
    val entry = synchronized(lock) {
      entries.remove(id).also { if (it == null && submitting) earlyEnds.add(id) }
    }
    entry?.source?.close()
  }

  fun retainExtra(source: PlayableSource) = synchronized(lock) { extras.add(source) }

  /** Called only after native destruction and all native call leases have ended. */
  fun closeAll() {
    val sources = synchronized(lock) {
      check(!submitting)
      (entries.values.map { it.source } + extras).also {
        entries.clear()
        extras.clear()
        earlyEnds.clear()
        earlyStarts.clear()
      }
    }
    sources.forEach { it.close() }
  }
}
