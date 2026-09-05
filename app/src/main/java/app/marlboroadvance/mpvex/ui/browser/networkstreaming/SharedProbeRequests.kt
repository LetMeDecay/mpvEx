package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Shares in-flight work until its last caller leaves; completed failures are not cached. */
internal class SharedProbeRequests<K, V>(private val scope: CoroutineScope) {
  private class Entry<V>(val result: Deferred<V>, var waiters: Int = 0)
  private val lock = Any()
  private val active = mutableMapOf<K, Entry<V>>()

  suspend fun run(key: K, probe: suspend () -> V): V {
    val entry = synchronized(lock) {
      val selected = active[key] ?: Entry(scope.async(start = CoroutineStart.LAZY) { probe() }).also { created ->
        active[key] = created
        created.result.invokeOnCompletion {
          synchronized(lock) { active.remove(key, created) }
        }
      }
      selected.also { it.waiters++ }
    }
    entry.result.start()
    return try {
      entry.result.await()
    } finally {
      synchronized(lock) {
        entry.waiters--
        if (entry.waiters == 0 && !entry.result.isCompleted) {
          active.remove(key, entry)
          entry.result.cancel()
        }
      }
    }
  }
}
