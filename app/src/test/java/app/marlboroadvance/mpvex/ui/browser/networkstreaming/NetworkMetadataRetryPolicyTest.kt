package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkMetadataRetryPolicyTest {
  @Test
  fun failedProbesBackOffAndCap() {
    assertEquals(0L, NetworkMetadataRetryPolicy.delayForFailure(0))
    assertEquals(5_000L, NetworkMetadataRetryPolicy.delayForFailure(1))
    assertEquals(10_000L, NetworkMetadataRetryPolicy.delayForFailure(2))
    assertEquals(NetworkMetadataRetryPolicy.MAX_DELAY_MS, NetworkMetadataRetryPolicy.delayForFailure(20))
  }
}
