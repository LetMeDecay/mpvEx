package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * One worker, with one pending load/pause/seek and at most 16 auxiliary jobs. Native calls
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
    val action: (suspend () -> Unit)? = null, val cleanup: () -> Unit = {})

  private val lock = Any()
  private val wakeup = Channel<Unit>(Channel.CONFLATED)
  private var pendingLoad: Command? = null
  private var pendingSeek: Command? = null
  private var pendingPause: Command? = null
  private val adjustments = linkedMapOf<String, Command>()
  private val auxiliary = ArrayDeque<Command>()

  private val worker: Job

  init {
    worker = scope.launch(dispatcher) {
      for (signal in wakeup) {
        while (true) {
          ensureActive()
          val command = synchronized(lock) {
            val next = pendingLoad ?: pendingPause ?: pendingSeek ?:
              adjustments.keys.firstOrNull()?.let { adjustments.remove(it) } ?: auxiliary.removeFirstOrNull()
            when {
              pendingLoad != null -> pendingLoad = null
              pendingPause != null -> pendingPause = null
              else -> pendingSeek = null
            }
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
          } finally {
            command.cleanup()
          }
        }
      }
    }
    worker.invokeOnCompletion { clearPending(close = true) }
  }

  private fun takeAuxiliary() = auxiliary.toList().also { auxiliary.clear() }

  fun load(arguments: List<String>, isValid: () -> Boolean) = replaceLoad(Command(arguments.toList(), isValid))

  fun loadAction(isValid: () -> Boolean, action: suspend () -> Unit) =
    replaceLoad(Command(emptyList(), isValid, action))

  private fun replaceLoad(command: Command) {
    val discarded = synchronized(lock) {
      pendingLoad = command
      pendingSeek = null
      pendingPause = null
      adjustments.clear()
      if (wakeup.trySend(Unit).isFailure) pendingLoad = null
      takeAuxiliary()
    }
    discarded.forEach { it.cleanup() }
  }

  /** Takes ownership even if rejected. Cleanup runs once after execution or discard. */
  fun auxiliary(isValid: () -> Boolean, cleanup: () -> Unit, action: suspend () -> Unit): Boolean {
    val command = Command(emptyList(), isValid, action, cleanup)
    val accepted = synchronized(lock) {
      if (auxiliary.size >= 16) false else {
        auxiliary.addLast(command)
        if (wakeup.trySend(Unit).isSuccess) true else {
          auxiliary.removeLast()
          false
        }
      }
    }
    if (!accepted) cleanup()
    return accepted
  }

  fun adjust(key: String, arguments: List<String>, isValid: () -> Boolean): Boolean = synchronized(lock) {
    if (key !in adjustments && adjustments.size >= 64) return@synchronized false
    adjustments[key] = Command(arguments.toList(), isValid)
    if (wakeup.trySend(Unit).isSuccess) true else {
      adjustments.remove(key)
      false
    }
  }

  fun pause(paused: Boolean, isValid: () -> Boolean) = synchronized(lock) {
    pendingPause = Command(listOf("set", "pause", if (paused) "yes" else "no"), isValid)
    if (wakeup.trySend(Unit).isFailure) pendingPause = null
  }

  fun retire() {
    clearPending(close = true)
    worker.cancel()
  }

  suspend fun awaitRetired() = worker.join()

  fun seek(arguments: List<String>, isValid: () -> Boolean) = synchronized(lock) {
    pendingSeek = Command(arguments.toList(), isValid)
    if (wakeup.trySend(Unit).isFailure) pendingSeek = null
  }

  fun clearPending(close: Boolean = false) {
    val discarded = synchronized(lock) {
      if (close) wakeup.close()
      pendingLoad = null
      pendingSeek = null
      pendingPause = null
      adjustments.clear()
      takeAuxiliary()
    }
    discarded.forEach { it.cleanup() }
  }
}
