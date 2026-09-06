package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.ProxyRequestScope
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.SharedRangeReads
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SharedRangeReadsTest {
  private fun key(name: String = "v1") = SharedRangeReads.Key(name, "http://localhost/test", null, 0, 3)

  @Test fun readersShareProgressButHaveIndependentPositionsAndCancellation() = runBlocking {
    withTimeout(5000) {
      val pool = SharedRangeReads()
      val firstOwner = ProxyRequestScope()
      val secondOwner = ProxyRequestScope()
      val release = CountDownLatch(1)
      val sourceCancelled = AtomicInteger()
      val opened = AtomicInteger()
      val cached = CompletableDeferred<ByteArray>()
      fun source(owner: ProxyRequestScope): InputStream {
        opened.incrementAndGet()
        owner.onCancel { sourceCancelled.incrementAndGet(); release.countDown() }
        return object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
          override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= 2) { check(release.await(4, TimeUnit.SECONDS)); owner.checkActive() }
            return super.read(b, off, minOf(2, len))
          }
        }
      }
      val first = pool.open(key(), firstOwner, ::source) { cached.complete(it.copyOf()) }!!
      val second = pool.open(key(), secondOwner, ::source) { error("Duplicate producer") }!!
      try {
        assertEquals(1, first.read())
        assertEquals(1, second.read())
        assertEquals(2, first.read())
        firstOwner.close()
        assertEquals(0, sourceCancelled.get())
        assertEquals(2, second.read())
        release.countDown()
        assertArrayEquals(byteArrayOf(3, 4), second.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), cached.await())
        assertEquals(1, opened.get())
      } finally {
        release.countDown()
        first.close()
        second.close()
        secondOwner.close()
      }
    }
  }

  @Test fun lastReaderCancelsSourceButSlotWaitsForWorkerToExit() = runBlocking {
    withTimeout(5000) {
      val pool = SharedRangeReads(maxDownloads = 1)
      val owner = ProxyRequestScope()
      val cancelled = CompletableDeferred<Unit>()
      val blocked = CountDownLatch(1)
      val release = CountDownLatch(1)
      val stream = pool.open(key(), owner, source = { producer ->
        producer.onCancel { cancelled.complete(Unit) }
        object : InputStream() {
          override fun read(): Int {
            blocked.countDown()
            check(release.await(4, TimeUnit.SECONDS))
            producer.checkActive()
            return -1
          }
        }
      }, completed = { error("Cancelled source cached") })!!
      try {
        withContext(Dispatchers.IO) { assertTrue(blocked.await(4, TimeUnit.SECONDS)) }
        owner.close()
        cancelled.await()
        assertEquals(1, pool.retainedDownloads())
        assertNull(pool.open(key("v2"), ProxyRequestScope(), { error("Capacity exceeded") }, {}))
        release.countDown()
        while (pool.retainedDownloads() != 0) delay(5)
        assertEquals(0, pool.retainedDownloads())
      } finally {
        release.countDown()
        stream.close()
      }
    }
  }

  @Test fun incompleteSourceCannotPublishAndCancelledReaderCannotReadBufferedData() = runBlocking {
    withTimeout(5000) {
      val pool = SharedRangeReads()
      val owner = ProxyRequestScope()
      val publications = AtomicInteger()
      try {
        pool.open(key(), owner, { ByteArrayInputStream(byteArrayOf(1)) }, { publications.incrementAndGet() })
          ?.use { it.readBytes() }
        fail("Short range accepted")
      } catch (_: IOException) { }
      owner.close()
      assertEquals(0, publications.get())
      while (pool.retainedDownloads() != 0) delay(5)
      val closedOwner = ProxyRequestScope().apply { close() }
      try { pool.open(key(), closedOwner, { error("Cancelled source opened") }, {}); fail("Cancelled request accepted") } catch (_: IOException) { }
    }
  }
}
