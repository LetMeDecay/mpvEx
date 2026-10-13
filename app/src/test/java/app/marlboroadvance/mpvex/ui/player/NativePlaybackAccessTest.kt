package app.marlboroadvance.mpvex.ui.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativePlaybackAccessTest {
  @Test(timeout = 5000)
  fun destructionWaitsForActiveCallAndCallbacksCannotReenterNative() {
    val access = NativePlaybackAccess()
    access.open()
    val pool = Executors.newFixedThreadPool(2)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val destroyed = CountDownLatch(1)
    try {
      val native = pool.submit {
        access.call {
          entered.countDown()
          check(release.await(3, TimeUnit.SECONDS))
          // This models a callback during destruction, on an already active call.
          assertNull(access.call { error("Retired engine must not be entered") })
        }
      }
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      access.retire()
      val close = pool.submit {
        access.close {
          assertNull(access.call { error("Destroy callback must not enter native") })
          destroyed.countDown()
        }
      }
      assertEquals(1L, destroyed.count)
      release.countDown()
      native.get(3, TimeUnit.SECONDS)
      close.get(3, TimeUnit.SECONDS)
      assertEquals(0L, destroyed.count)
      assertNull(access.call { error("Closed engine must not be entered") })
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test(timeout = 5000)
  fun failingCallReleasesLeaseAndNextEngineCanOpen() {
    val access = NativePlaybackAccess()
    assertNull(access.call { "before open" })
    access.open()
    try {
      access.call { error("native failure") }
      fail("Expected native failure")
    } catch (_: IllegalStateException) {
      // The original error is preserved; it must not strand an active lease.
    }
    access.close {}
    access.open()
    assertEquals("new engine", access.call { "new engine" })
    access.close {}
  }
}
