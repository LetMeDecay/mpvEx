package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailDiskStoreTest {
  @get:Rule val temporary = TemporaryFolder()
  private val key = "1::/video.mp4::100::::1"

  @Test fun encodingLeavesGenerationReadsFreeAndStaleWritesKeepTheOldFile() = runBlocking {
    withTimeout(10000) {
      val store = ThumbnailDiskStore(temporary.newFolder("thumbnails"))
      val state = MetadataCacheGeneration()
      val initialToken = state.token(key)
      val callerThread = Thread.currentThread().id
      assertTrue(store.write(1, key, { true }) { it.writeText("old"); true })
      val encoding = CountDownLatch(1)
      val release = CountDownLatch(1)
      val pending = async {
        store.write(1, key, { state.token(key) == initialToken }) {
            assertNotEquals(callerThread, Thread.currentThread().id)
            it.writeText("stale")
            encoding.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }
      }
      try {
        withContext(Dispatchers.IO) { assertTrue(encoding.await(5, TimeUnit.SECONDS)) }
        // This must complete while encoding remains blocked, not after it finishes.
        withTimeout(1000) {
          withContext(Dispatchers.Default) {
            assertEquals("old", store.decode(1, key, { true }) { it.reader().readText() })
            assertEquals(initialToken, state.token(key))
            state.invalidate(1, mapOf(key to "/video.mp4")) {}
          }
        }
      } finally {
        release.countDown()
      }
      assertFalse(pending.await())
      assertEquals("old", store.decode(1, key, { true }) { it.reader().readText() })
    }
  }

  @Test fun clearDuringDecodeCompletesWithoutWaitingAndRejectsOldSnapshot() = runBlocking {
    withTimeout(10000) {
      val store = ThumbnailDiskStore(temporary.newFolder("thumbnails"))
      val state = MetadataCacheGeneration()
      val token = state.token(key)
      var published: String? = null
      store.write(1, key, { true }) { it.writeText("old"); true }
      val decoding = CountDownLatch(1)
      val release = CountDownLatch(1)
      val reading = async {
        val value = store.decode(1, key, { state.token(key) == token }) {
          decoding.countDown()
          check(release.await(5, TimeUnit.SECONDS))
          it.reader().readText()
        }
        assertEquals("old", value) // Open descriptor survives cache deletion.
        state.publish(key, token) { published = value }
      }
      try {
        withContext(Dispatchers.IO) { assertTrue(decoding.await(5, TimeUnit.SECONDS)) }
        withTimeout(1000) {
          store.transaction {
            state.clear { published = null }
            clear()
          }
        }
        assertFalse(store.transaction { exists(1, key) })
      } finally {
        release.countDown()
      }
      assertFalse(reading.await())
      assertNull(published)
      store.write(1, key, { true }) { it.writeText("fresh"); true }
      assertEquals("fresh", store.decode(1, key, { true }) { it.reader().readText() })
    }
  }

  @Test fun failedEncodingNeverReplacesValidBytesOrLeavesTemporaryFiles() = runBlocking {
    val root = temporary.newFolder("thumbnails")
    val store = ThumbnailDiskStore(root)
    store.write(1, key, { true }) { it.writeText("valid"); true }
    assertFalse(store.write(1, key, { true }) { it.writeText("partial"); false })
    assertEquals("valid", store.decode(1, key, { true }) { it.reader().readText() })
    assertEquals(1, root.walkTopDown().count { it.isFile })
  }

  @Test fun twoDecodesOverlapButThirdWaitsForCapacity() = runBlocking {
    withTimeout(10000) {
      val store = ThumbnailDiskStore(temporary.newFolder("thumbnails"))
      store.write(1, key, { true }) { it.writeText("image"); true }
      val entered = CountDownLatch(2)
      val release = CountDownLatch(1)
      val active = AtomicInteger()
      val maximum = AtomicInteger()
      suspend fun decode() = store.decode(1, key, { true }) {
        val count = active.incrementAndGet()
        maximum.updateAndGet { previous -> maxOf(previous, count) }
        try {
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
          it.reader().readText()
        } finally {
          active.decrementAndGet()
        }
      }
      val first = async { decode() }
      val second = async { decode() }
      var third: Deferred<String?>? = null
      try {
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        third = async(start = CoroutineStart.UNDISPATCHED) { decode() }
        assertNull(withTimeoutOrNull(200) { third.await() })
        assertEquals(2, active.get())
        withTimeout(1000) { assertTrue(store.transaction { exists(1, key) }) }
      } finally {
        release.countDown()
      }
      assertEquals("image", first.await())
      assertEquals("image", second.await())
      assertEquals("image", third!!.await())
      assertEquals(2, maximum.get())
    }
  }

  @Test fun clearDuringEncodingCannotRestoreOldFileOrReplaceNewGeneration() = runBlocking {
    withTimeout(10000) {
      val root = temporary.newFolder("thumbnails")
      val store = ThumbnailDiskStore(root)
      val state = MetadataCacheGeneration()
      val oldToken = state.token(key)
      val encoding = CountDownLatch(1)
      val release = CountDownLatch(1)
      val writing = async {
        store.write(1, key, { state.token(key) == oldToken }) {
          it.writeText("old")
          encoding.countDown()
          check(release.await(5, TimeUnit.SECONDS))
          true
        }
      }
      try {
        withContext(Dispatchers.IO) { assertTrue(encoding.await(5, TimeUnit.SECONDS)) }
        withTimeout(1000) { store.transaction { state.clear {}; clear() } }
        store.write(1, key, { true }) { it.writeText("fresh"); true }
      } finally {
        release.countDown()
      }
      assertFalse(writing.await())
      assertEquals("fresh", store.decode(1, key, { true }) { it.reader().readText() })
      assertEquals(1, root.walkTopDown().count { it.isFile })
    }
  }
}
