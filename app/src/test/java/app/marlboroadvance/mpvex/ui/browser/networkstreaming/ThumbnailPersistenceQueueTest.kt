package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ThumbnailPersistenceQueueTest {
  @Test fun slowWriterAllowsOneQueuedImageThenBackpressuresTheProducer() = runBlocking {
    withTimeout(5000) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val finished = CompletableDeferred<Unit>()
      val written = mutableListOf<Int>()
      val queue = ThumbnailPersistenceQueue<Int>(scope, persist = {
        if (it == 1) { started.complete(Unit); release.await() }
        written.add(it)
        if (it == 3) finished.complete(Unit)
      }, onFailure = { throw AssertionError(it) })
      try {
        queue.enqueue(1) // Returns before persistence completes.
        started.await()
        queue.enqueue(2)
        val displayed = CompletableDeferred<Int>()
        val producer = async(start = CoroutineStart.UNDISPATCHED) {
          displayed.complete(3) // Same ordering as ThumbnailDispatcher.
          queue.enqueue(3)
        }
        assertEquals(3, displayed.await().toInt())
        assertFalse(producer.isCompleted)
        release.complete(Unit)
        producer.await()
        finished.await()
        assertEquals(listOf(1, 2, 3), written)
      } finally {
        scope.cancel()
      }
    }
  }

  @Test fun persistenceFailureDoesNotStopLaterImagesAndShutdownRejectsNewWork() = runBlocking {
    withTimeout(5000) {
      val parent = SupervisorJob()
      val scope = CoroutineScope(parent + Dispatchers.Default)
      val failure = CompletableDeferred<Exception>()
      val saved = CompletableDeferred<Int>()
      val queue = ThumbnailPersistenceQueue<Int>(scope, persist = {
        if (it == 1) throw IOException("test write failure")
        saved.complete(it)
      }, onFailure = { failure.complete(it) })
      try {
        queue.enqueue(1)
        assertTrue(failure.await() is IOException)
        queue.enqueue(2)
        assertEquals(2, saved.await().toInt())
      } finally {
        parent.cancelAndJoin()
      }
      try {
        queue.enqueue(3)
        fail("Cancelled queue must reject new work")
      } catch (_: CancellationException) {
        // The caller cannot get stuck behind a stopped writer.
      }
    }
  }
}
