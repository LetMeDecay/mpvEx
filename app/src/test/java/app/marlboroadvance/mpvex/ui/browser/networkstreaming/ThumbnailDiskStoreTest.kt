package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
      store.transaction { assertTrue(write(1, key, { true }) { it.writeText("old"); true }) }
      val encoding = CountDownLatch(1)
      val release = CountDownLatch(1)
      val pending = async {
        store.transaction {
          write(1, key, { state.token(key) == initialToken }) {
            assertNotEquals(callerThread, Thread.currentThread().id)
            it.writeText("stale")
            encoding.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
          }
        }
      }
      try {
        withContext(Dispatchers.IO) { assertTrue(encoding.await(5, TimeUnit.SECONDS)) }
        // This must complete while encoding remains blocked, not after it finishes.
        withTimeout(1000) {
          withContext(Dispatchers.Default) {
            assertEquals(initialToken, state.token(key))
            state.invalidate(1, mapOf(key to "/video.mp4")) {}
          }
        }
      } finally {
        release.countDown()
      }
      assertFalse(pending.await())
      assertEquals("old", store.transaction { read(1, key) { it.readText() } })
    }
  }

  @Test fun clearQueuedDuringDecodeRemovesBothDiskAndPublishedMemory() = runBlocking {
    withTimeout(10000) {
      val store = ThumbnailDiskStore(temporary.newFolder("thumbnails"))
      val state = MetadataCacheGeneration()
      val memory = mutableMapOf<String, String>()
      store.transaction { write(1, key, { true }) { it.writeText("old"); true } }
      val decoding = CountDownLatch(1)
      val release = CountDownLatch(1)
      val reading = async {
        store.transaction {
          val token = state.token(key)
          val value = read(1, key) {
            decoding.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            it.readText()
          }
          state.publish(key, token) { memory[key] = value!! }
        }
      }
      withContext(Dispatchers.IO) { assertTrue(decoding.await(5, TimeUnit.SECONDS)) }
      val clearing = async(start = CoroutineStart.UNDISPATCHED) {
        store.transaction {
          state.clear { memory.clear() }
          clear()
        }
      }
      release.countDown()
      reading.await()
      clearing.await()
      assertTrue(memory.isEmpty())
      assertFalse(store.transaction { exists(1, key) })
      // A new generation can recreate the directory after a successful clear.
      store.transaction { write(1, key, { true }) { it.writeText("fresh"); true } }
      assertEquals("fresh", store.transaction { read(1, key) { it.readText() } })
    }
  }

  @Test fun failedEncodingNeverReplacesValidBytesOrLeavesTemporaryFiles() = runBlocking {
    val root = temporary.newFolder("thumbnails")
    val store = ThumbnailDiskStore(root)
    store.transaction {
      write(1, key, { true }) { it.writeText("valid"); true }
      assertFalse(write(1, key, { true }) { it.writeText("partial"); false })
      assertEquals("valid", read(1, key) { it.readText() })
    }
    assertEquals(1, root.walkTopDown().count { it.isFile })
  }
}
