package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * One writer and one queued image. The single thumbnail producer publishes its
 * result before enqueueing; when full it waits here before generating another.
 * At most three images are retained by persistence, including that producer.
 */
internal class ThumbnailPersistenceQueue<T>(
  scope: CoroutineScope,
  persist: suspend (T) -> Unit,
  onFailure: (Exception) -> Unit,
) {
  private val queue = Channel<T>(capacity = 1)

  init {
    scope.launch {
      for (item in queue) {
        try {
          persist(item)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          onFailure(e)
        }
      }
    }.invokeOnCompletion { queue.cancel() }
  }

  suspend fun enqueue(item: T) {
    queue.send(item)
  }
}
