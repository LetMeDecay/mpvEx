package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Single consumer; retired work may finish native IO but never accepts new subscribers. */
internal class PriorityProbeDispatcher<K, I, V>(
  scope: CoroutineScope,
  private val produce: suspend (I, (V?) -> Unit) -> Unit,
  private val onFailure: (Exception) -> Unit,
  private val maxPending: Int = 64,
) {
  private class Request<K, I, V>(val key: K, val input: I, var priority: Int) {
    val result = CompletableDeferred<V?>()
    var waiters = 0
  }
  private val lock = Any()
  private val wakeUp = Channel<Unit>(Channel.CONFLATED)
  private val requests = ThumbnailPriorityQueue<K, Int> { it }
  private val byKey = mutableMapOf<K, Request<K, I, V>>()
  private var running: Request<K, I, V>? = null
  private var runningJob: Job? = null
  private var closed = false

  init {
    require(maxPending > 0)
    scope.launch {
      for (ignored in wakeUp) {
        while (true) {
          val request = synchronized(lock) {
            requests.poll()?.let { byKey[it.key]?.also { selected -> running = selected } }
          } ?: break
          val job = launch(start = CoroutineStart.LAZY) {
            if (!synchronized(lock) { canStart(request) }) return@launch
            try {
              produce(request.input) { result ->
                synchronized(lock) {
                  if (byKey[request.key] === request && !request.result.isCancelled) {
                    request.result.complete(result)
                  }
                }
              }
              request.result.complete(null)
            } catch (e: CancellationException) { request.result.cancel(e) }
            catch (e: Exception) { onFailure(e); request.result.complete(null) }
          }
          val assigned = synchronized(lock) {
            if (canStart(request)) { runningJob = job; true } else false
          }
          if (assigned) job.start() else { request.result.cancel(); job.cancel() }
          job.join() // Even cancelled synchronous native work must leave before the next producer.
          synchronized(lock) {
            byKey.remove(request.key, request)
            if (running === request) { running = null; runningJob = null }
          }
        }
      }
    }.invokeOnCompletion {
      synchronized(lock) {
        closed = true
        byKey.values.forEach { it.result.cancel() }
        byKey.clear()
        requests.entries().forEach { requests.cancel(it.key) }
        wakeUp.close()
      }
    }
  }

  private fun canStart(request: Request<K, I, V>) = ThumbnailDispatchGuard.canStart(
    ThumbnailDispatchSnapshot(request.waiters, request.result.isCancelled, byKey[request.key] === request),
  )

  suspend fun submit(key: K, input: I, priority: Int): V? {
    val request = synchronized(lock) {
      if (closed) return null
      val existing = byKey[key]
      val selected = if (existing != null && !existing.result.isCancelled) {
        promote(key, priority)
        existing
      } else {
        if (requests.size() >= maxPending) {
          val worst = requests.entries().maxWithOrNull(compareBy<ThumbnailPriorityQueue.Entry<K, Int>> { it.priority }
            .thenBy { it.sequence })!!
          if (priority > worst.priority) return null
          byKey[worst.key]?.let(::retire)
        }
        Request<K, I, V>(key, input, priority).also {
          byKey[key] = it
          requests.offer(key, priority)
          wakeUp.trySend(Unit)
        }
      }
      selected.also { it.waiters++ }
    }
    return try { request.result.await() } finally {
      synchronized(lock) {
        request.waiters--
        if (request.waiters == 0 && !request.result.isCompleted) retire(request)
      }
    }
  }

  // Caller holds lock. Remove identity and cancel result before cancelling native work.
  private fun retire(request: Request<K, I, V>) {
    if (byKey.remove(request.key, request)) requests.cancel(request.key)
    request.result.cancel()
    if (running === request) runningJob?.cancel()
  }

  fun promote(key: K, priority: Int) = synchronized(lock) {
    val request = byKey[key] ?: return@synchronized
    if (priority < request.priority) {
      request.priority = priority
      if (request !== running) requests.promote(key, priority)
    }
  }

  fun cancelBackgroundExcept(retainedKeys: Set<K>, minimumPriority: Int) = synchronized(lock) {
    byKey.values.filter { it.priority >= minimumPriority && it.key !in retainedKeys && !it.result.isCompleted }
      .toList().forEach(::retire)
  }
}
