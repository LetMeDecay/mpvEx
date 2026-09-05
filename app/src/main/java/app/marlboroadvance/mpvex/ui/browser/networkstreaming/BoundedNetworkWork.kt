package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Reuses a fixed number of workers rather than allocating a coroutine per file. */
internal suspend fun <T> List<T>.forEachNetworkItem(
  concurrency: Int,
  action: suspend (T) -> Unit,
) = coroutineScope {
  val next = AtomicInteger()
  repeat(minOf(size, NetworkPreloadPolicy.clampThreads(concurrency))) {
    launch {
      while (true) {
        ensureActive()
        val index = next.getAndIncrement()
        if (index >= size) break
        action(this@forEachNetworkItem[index])
      }
    }
  }
}
