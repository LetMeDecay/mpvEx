package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailDispatchGuardTest {
  @Test
  fun onlyRegisteredRequestsWithWaitersMayStart() {
    assertTrue(
      ThumbnailDispatchGuard.canStart(
        ThumbnailDispatchSnapshot(waiters = 1, resultCancelled = false, stillRegistered = true),
      ),
    )
    assertFalse(
      ThumbnailDispatchGuard.canStart(
        ThumbnailDispatchSnapshot(waiters = 0, resultCancelled = false, stillRegistered = true),
      ),
    )
    assertFalse(
      ThumbnailDispatchGuard.canStart(
        ThumbnailDispatchSnapshot(waiters = 1, resultCancelled = true, stillRegistered = true),
      ),
    )
    assertFalse(
      ThumbnailDispatchGuard.canStart(
        ThumbnailDispatchSnapshot(waiters = 1, resultCancelled = false, stillRegistered = false),
      ),
    )
  }
}
