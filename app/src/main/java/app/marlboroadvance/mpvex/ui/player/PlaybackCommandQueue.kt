package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * One worker, with at most one pending load and one pending seek. Native calls
 * already in progress cannot be interrupted; validity is checked at dispatch.
 * Retirement cancels pending work; join waits for an in-flight native call.
 */
internal class PlaybackCommandQueue(
  scope: CoroutineScope,
  dispatcher: CoroutineDispatcher,
  private val execute: suspend (List<String>) -> Unit,
  private val onFailure: (Exception) -> Unit,
) {
  private data class Command(val arguments: List<String>, val isValid: () -> Boolean,
    val action: (suspend () -> Unit)? = null)

  private val lock = Any()
  private val wakeup = Channel<Unit>(Channel.CONFLATED)
  private var pendingLoad: Command? = null
  private var pendingSeek: Command? = null

  private val worker: Job

  init {
    worker = scope.launch(dispatcher) {
      for (signal in wakeup) {
        while (true) {
          ensureActive()
          val command = synchronized(lock) {
            val next = pendingLoad ?: pendingSeek
            if (pendingLoad != null) pendingLoad = null else pendingSeek = null
            next
          } ?: break
          try {
            if (command.isValid()) {
              val action = command.action
              if (action != null) action() else execute(command.arguments)
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (failure: Exception) {
            // A failed native call must not permanently disable later requests.
            onFailure(failure)
          }
        }
      }
    }
    worker.invokeOnCompletion {
      synchronized(lock) {
        wakeup.close()
        pendingLoad = null
        pendingSeek = null
      }
    }
  }

  fun load(arguments: List<String>, isValid: () -> Boolean) = synchronized(lock) {
    pendingLoad = Command(arguments.toList(), isValid)
    pendingSeek = null
    if (wakeup.trySend(Unit).isFailure) pendingLoad = null
  }

  fun loadAction(isValid: () -> Boolean, action: suspend () -> Unit) = synchronized(lock) {
    pendingLoad = Command(emptyList(), isValid, action)
    pendingSeek = null
    if (wakeup.trySend(Unit).isFailure) pendingLoad = null
  }

  fun retire() {
    synchronized(lock) {
      wakeup.close()
      pendingLoad = null
      pendingSeek = null
    }
    worker.cancel()
  }

  suspend fun awaitRetired() = worker.join()

  fun seek(arguments: List<String>, isValid: () -> Boolean) = synchronized(lock) {
    pendingSeek = Command(arguments.toList(), isValid)
    if (wakeup.trySend(Unit).isFailure) pendingSeek = null
  }

  fun clearPending() = synchronized(lock) {
    pendingLoad = null
    pendingSeek = null
  }
}
