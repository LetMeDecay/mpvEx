package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PriorityProbeDispatcherTest {
  @Test(timeout = 5000) fun boundedQueueRejectsLessUrgentWorkWhileNativeIsBusy() = runBlocking {
    val owner = Job()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val calls = mutableListOf<Int>()
    val dispatcher = PriorityProbeDispatcher<String, Int, Int>(CoroutineScope(coroutineContext + owner),
      produce = { value, publish ->
        calls.add(value)
        if (value == 0) { entered.complete(Unit); release.await() }
        publish(value)
      }, onFailure = { throw AssertionError(it) }, maxPending = 2)
    try {
      val active = async { dispatcher.submit("active", 0, 0) }
      entered.await()
      val first = async { dispatcher.submit("one", 1, 1) }
      val second = async { dispatcher.submit("two", 2, 1) }
      yield()
      repeat(100) { assertNull(dispatcher.submit("background-$it", 3, 3)) }
      release.complete(Unit)
      active.await(); first.await(); second.await()
      assertEquals(listOf(0, 1, 2), calls)
    } finally { release.complete(Unit); owner.cancelAndJoin() }
  }

  @Test(timeout = 5000) fun newWaiterNeverJoinsRetiredNativeWork() = runBlocking {
    val owner = Job()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var runs = 0
    val dispatcher = PriorityProbeDispatcher<String, Int, Int>(CoroutineScope(coroutineContext + owner),
      produce = { value, publish ->
        runs++
        if (value == 1) withContext(NonCancellable) { entered.complete(Unit); release.await() }
        publish(value)
      }, onFailure = { throw AssertionError(it) })
    try {
      val first = async { dispatcher.submit("same", 1, 1) }
      entered.await()
      first.cancelAndJoin()
      val second = async { dispatcher.submit("same", 2, 1) }
      yield()
      assertFalse(second.isCompleted)
      assertEquals(1, runs)
      release.complete(Unit)
      assertEquals(2, second.await())
      assertEquals(2, runs)
    } finally { release.complete(Unit); owner.cancelAndJoin() }
  }

  @Test(timeout = 5000) fun backgroundRetirementPreservesReplacementAndPriorityPromotion() = runBlocking {
    val owner = Job()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val order = mutableListOf<Int>()
    val dispatcher = PriorityProbeDispatcher<String, Int, Int>(CoroutineScope(coroutineContext + owner),
      produce = { value, publish ->
        order.add(value)
        if (value == 1) withContext(NonCancellable) { entered.complete(Unit); release.await() }
        publish(value)
      }, onFailure = { throw AssertionError(it) })
    try {
      val first = async { dispatcher.submit("same", 1, 2) }
      entered.await()
      dispatcher.cancelBackgroundExcept(emptySet(), 2)
      val replacement = async { dispatcher.submit("same", 2, 2) }
      val visible = async { dispatcher.submit("visible", 3, 1) }
      yield()
      dispatcher.promote("same", 0)
      release.complete(Unit)
      try { first.await(); fail("Retired work published") } catch (_: CancellationException) { }
      assertEquals(2, replacement.await())
      assertEquals(3, visible.await())
      assertEquals(listOf(1, 2, 3), order)
    } finally { release.complete(Unit); owner.cancelAndJoin() }
  }
}
