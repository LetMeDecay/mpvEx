package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import java.io.Closeable
import java.io.IOException

/** Owns partially opened transports, releasing children before their parents. */
internal class ResourceLease : Closeable {
  private val lock = Any()
  private var closed = false
  private val resources = mutableListOf<AutoCloseable>()

  fun <T : AutoCloseable> own(resource: T): T {
    val rejected = synchronized(lock) {
      if (closed) true else { resources.add(resource); false }
    }
    if (rejected) {
      runCatching { resource.close() }
      throw IOException("Transport retired during preparation")
    }
    return resource
  }

  override fun close() {
    val pending = synchronized(lock) {
      if (closed) return
      closed = true
      resources.asReversed().toList().also { resources.clear() }
    }
    pending.forEach { runCatching { it.close() } }
  }
}
