package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

class PlaybackCommandQueueTest {
  @Test(timeout = 5000)
  fun auxiliaryResourcesAreBoundedAndReleasedWhenReplacedOrInvalid() = runBlocking {
    val owner = Job()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var cleaned = 0
    var executed = 0
    val queue = PlaybackCommandQueue(CoroutineScope(coroutineContext + owner),
      coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
      execute = {}, onFailure = { throw AssertionError(it) })
    try {
      queue.loadAction({ true }) { entered.complete(Unit); release.await() }
      entered.await()
      repeat(16) {
        assertEquals(true, queue.auxiliary({ true }, { cleaned++ }) { executed++ })
      }
      assertEquals(false, queue.auxiliary({ true }, { cleaned++ }) { executed++ })
      assertEquals(1, cleaned)
      queue.load(listOf("loadfile", "next")) { true }
      assertEquals(17, cleaned)
      queue.auxiliary({ false }, { cleaned++ }) { executed++ }
      release.complete(Unit)
      yield()
      assertEquals(18, cleaned)
      assertEquals(0, executed)
      queue.auxiliary({ true }, { cleaned++ }) { executed++ }
      yield()
      assertEquals(19, cleaned)
      assertEquals(1, executed)
      queue.retire()
      queue.awaitRetired()
      assertEquals(false, queue.auxiliary({ true }, { cleaned++ }) { executed++ })
      assertEquals(20, cleaned)
    } finally { owner.cancelAndJoin() }
  }

  @Test(timeout = 5000)
  fun inFlightAuxiliaryFinishesBeforeNextLoadAndRetirementCleanup() = runBlocking {
    val owner = Job()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val order = mutableListOf<String>()
    val queue = PlaybackCommandQueue(CoroutineScope(coroutineContext + owner),
      coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
      execute = { order.add("load") }, onFailure = { throw AssertionError(it) })
    try {
      queue.auxiliary({ true }, { order.add("cleanup") }) {
        withContext(NonCancellable) { entered.complete(Unit); release.await(); order.add("subtitle") }
      }
      entered.await()
      queue.load(listOf("loadfile", "next")) { true }
      yield()
      assertEquals(emptyList<String>(), order)
      release.complete(Unit)
      yield()
      assertEquals(listOf("subtitle", "cleanup", "load"), order)
      queue.auxiliary({ true }, { order.add("discard") }) { error("must not run") }
      queue.retire()
      queue.awaitRetired()
      assertEquals("discard", order.last())
    } finally { owner.cancelAndJoin() }
  }

  @Test(timeout = 5000)
  fun pauseIsBoundedAndRetiredWithItsFile() = runBlocking {
    val owner = Job()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val calls = mutableListOf<List<String>>()
    val queue = PlaybackCommandQueue(
      CoroutineScope(coroutineContext + owner),
      coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
      execute = { calls.add(it) }, onFailure = { throw AssertionError(it) },
    )
    try {
      queue.loadAction({ true }) { entered.complete(Unit); release.await() }
      entered.await()
      repeat(1000) { queue.pause(it % 2 == 0) { true } }
      queue.seek(listOf("seek", "10")) { true }
      release.complete(Unit)
      yield()
      assertEquals(listOf(listOf("set", "pause", "no"), listOf("seek", "10")), calls)
      queue.pause(true) { true }
      queue.load(listOf("loadfile", "next")) { true }
      yield()
      assertEquals(listOf("loadfile", "next"), calls.last())
      assertEquals(3, calls.size)
      queue.pause(true) { false }
      yield()
      queue.pause(true) { true }
      queue.retire()
      queue.awaitRetired()
      assertEquals(3, calls.size)
    } finally { owner.cancelAndJoin() }
  }

  @Test(timeout = 5000)
  fun blockedLoadKeepsOnlyLatestLoadAndSeek() = runBlocking {
    val owner = Job()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val calls = mutableListOf<List<String>>()
    val queue = PlaybackCommandQueue(
      CoroutineScope(coroutineContext + owner),
      coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
      execute = {
        calls.add(it)
        if (it == listOf("loadfile", "a")) {
          entered.complete(Unit)
          release.await()
        }
      },
      onFailure = { throw AssertionError(it) },
    )
    try {
      queue.load(listOf("loadfile", "a")) { true }
      entered.await()
      queue.seek(listOf("seek", "old")) { true }
      queue.load(listOf("loadfile", "b")) { true }
      queue.load(listOf("loadfile", "c")) { true }
      repeat(1000) { queue.seek(listOf("seek", "$it")) { true } }
      release.complete(Unit)
      yield()
      assertEquals(
        listOf(listOf("loadfile", "a"), listOf("loadfile", "c"), listOf("seek", "999")),
        calls,
      )
    } finally {
      owner.cancelAndJoin()
    }
  }

  @Test(timeout = 5000)
  fun validityIsCheckedAtDispatchAndClearDropsPendingWork() = runBlocking {
    val owner = Job()
    val calls = mutableListOf<List<String>>()
    val queue = PlaybackCommandQueue(
      CoroutineScope(coroutineContext + owner),
      coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
      execute = { calls.add(it) },
      onFailure = { throw AssertionError(it) },
    )
    try {
      var current = true
      queue.load(listOf("loadfile", "a")) { current }
      current = false
      yield()
      assertEquals(emptyList<List<String>>(), calls)
      queue.load(listOf("loadfile", "b")) { true }
      queue.seek(listOf("seek", "10")) { true }
      queue.clearPending()
      yield()
      assertEquals(emptyList<List<String>>(), calls)
      queue.load(listOf("loadfile", "c")) { true }
      yield()
      assertEquals(listOf(listOf("loadfile", "c")), calls)
    } finally {
      owner.cancelAndJoin()
    }
  }

  @Test(timeout = 5000)
  fun failedCommandDoesNotDisableQueueAndCancelledOwnerRejectsWork() = runBlocking {
    val owner = Job()
    var failures = 0
    val calls = mutableListOf<List<String>>()
    val queue = PlaybackCommandQueue(
      CoroutineScope(coroutineContext + owner),
      coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
      execute = {
        if (it == listOf("loadfile", "bad")) error("native failure")
        calls.add(it)
      },
      onFailure = { failures++ },
    )
    try {
      queue.load(listOf("loadfile", "bad")) { true }
      yield()
      queue.load(listOf("loadfile", "good")) { true }
      yield()
      assertEquals(1, failures)
      assertEquals(listOf(listOf("loadfile", "good")), calls)
    } finally {
      owner.cancelAndJoin()
    }
    queue.load(listOf("loadfile", "after cancellation")) { true }
    yield()
    assertEquals(1, calls.size)
  }

  @Test(timeout = 5000)
  fun replacementLoadDropsSeekForPreviousFile() = runBlocking {
    val owner = Job()
    val calls = mutableListOf<List<String>>()
    val queue = PlaybackCommandQueue(
      CoroutineScope(coroutineContext + owner),
      coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
      execute = { calls.add(it) },
      onFailure = { throw AssertionError(it) },
    )
    try {
      queue.seek(listOf("seek", "120")) { true }
      queue.load(listOf("loadfile", "next")) { true }
      yield()
      assertEquals(listOf(listOf("loadfile", "next")), calls)
    } finally {
      owner.cancelAndJoin()
    }
  }

  @Test(timeout = 5000)
  fun immediateDispatchDoesNotLoseWakeup() = runBlocking {
    val owner = Job()
    val calls = mutableListOf<List<String>>()
    val queue = PlaybackCommandQueue(
      CoroutineScope(coroutineContext + owner),
      Dispatchers.Unconfined,
      execute = { calls.add(it) },
      onFailure = { throw AssertionError(it) },
    )
    try {
      queue.load(listOf("loadfile", "a")) { true }
      queue.seek(listOf("seek", "10")) { true }
      assertEquals(listOf(listOf("loadfile", "a"), listOf("seek", "10")), calls)
    } finally {
      owner.cancelAndJoin()
    }
  }
  @Test(timeout = 5000)
  fun retirementWaitsForNativeCallAndRejectsPendingAndLaterCommands() = runBlocking {
    val owner = Job()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val calls = mutableListOf<String>()
    val queue = PlaybackCommandQueue(
      CoroutineScope(coroutineContext + owner),
      coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
      execute = { error("Only the load action should run") },
      onFailure = { throw AssertionError(it) },
    )
    try {
      queue.loadAction({ true }) {
        calls.add("load")
        entered.complete(Unit)
        // Model a JNI call which cannot be cancelled by coroutine cancellation.
        withContext(NonCancellable) { release.await() }
        calls.add("load returned")
      }
      entered.await()
      queue.seek(listOf("seek", "old")) { true }
      queue.retire()
      queue.load(listOf("loadfile", "late")) { true }
      val shutdown = async {
        queue.awaitRetired()
        calls.add("stop")
        calls.add("destroy")
      }
      yield()
      assertEquals(listOf("load"), calls)
      release.complete(Unit)
      shutdown.await()
      assertEquals(listOf("load", "load returned", "stop", "destroy"), calls)
    } finally {
      release.complete(Unit)
      owner.cancelAndJoin()
    }
  }

}
