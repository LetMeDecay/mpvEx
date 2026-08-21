package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPreloadPolicyTest {
  @Test
  fun preloadThreadsAreClampedToOneThroughSix() {
    assertEquals(1, NetworkPreloadPolicy.clampThreads(Int.MIN_VALUE))
    (1..6).forEach { value ->
      assertEquals(value, NetworkPreloadPolicy.clampThreads(value))
    }
    assertEquals(6, NetworkPreloadPolicy.clampThreads(7))
    assertEquals(6, NetworkPreloadPolicy.clampThreads(Int.MAX_VALUE))
  }
}
