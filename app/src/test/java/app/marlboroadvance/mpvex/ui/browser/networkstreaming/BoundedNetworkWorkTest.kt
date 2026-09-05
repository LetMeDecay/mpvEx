package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BoundedNetworkWorkTest {
  @Test fun largeDirectoryUsesFixedWorkersAndVisitsEveryItemOnce() = runBlocking {
    val workers = mutableSetOf<Job>()
    val visited = mutableListOf<Int>()
    (0 until 1000).toList().forEachNetworkItem(3) { item ->
      workers += currentCoroutineContext()[Job]!!
      visited += item
      yield()
    }
    assertEquals(3, workers.size)
    assertEquals((0 until 1000).toList(), visited.sorted())
  }

  @Test fun cancellingDirectoryStopsWorkersBeforeRemainingItemsStart() = runBlocking {
    withTimeout(5000) {
      val started = CompletableDeferred<Unit>()
      var calls = 0
      val work = launch {
        (0 until 1000).toList().forEachNetworkItem(2) {
          if (++calls == 2) started.complete(Unit)
          awaitCancellation()
        }
      }
      started.await()
      work.cancelAndJoin()
      assertEquals(2, calls)
    }
  }
}
