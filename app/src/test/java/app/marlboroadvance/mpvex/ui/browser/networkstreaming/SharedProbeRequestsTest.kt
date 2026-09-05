package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SharedProbeRequestsTest {
  @Test fun duplicateCallersShareWorkAndOneCancellationDoesNotStopTheOther() = runBlocking {
    withTimeout(5000) {
      val requests = SharedProbeRequests<String, Int>(this)
      val gate = CompletableDeferred<Unit>()
      var calls = 0
      val first = async(start = CoroutineStart.UNDISPATCHED) { requests.run("same") { calls++; gate.await(); 42 } }
      val second = async(start = CoroutineStart.UNDISPATCHED) { requests.run("same") { error("duplicate") } }
      first.cancelAndJoin()
      gate.complete(Unit)
      assertEquals(42, second.await())
      assertEquals(1, calls)
    }
  }

  @Test fun lastCallerCancellationReleasesWorkAndAllowsRetry() = runBlocking {
    withTimeout(5000) {
      val requests = SharedProbeRequests<String, Int>(this)
      val started = CompletableDeferred<Unit>()
      val stopped = CompletableDeferred<Unit>()
      val caller = async(start = CoroutineStart.UNDISPATCHED) {
        requests.run("same") {
          started.complete(Unit)
          try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
      }
      started.await()
      caller.cancelAndJoin()
      stopped.await()
      assertEquals(7, requests.run("same") { 7 })
    }
  }

  @Test fun failedResultsAndDifferentGenerationsAreNotReused() = runBlocking {
    withTimeout(5000) {
      val requests = SharedProbeRequests<String, Int>(this)
      val gate = CompletableDeferred<Unit>()
      val old = async(start = CoroutineStart.UNDISPATCHED) { requests.run("file#0") { gate.await(); 1 } }
      assertEquals(2, requests.run("file#1") { 2 })
      gate.complete(Unit)
      assertEquals(1, old.await())
      assertEquals(0, requests.run("failure") { 0 })
      assertEquals(3, requests.run("failure") { 3 })
    }
  }
}
