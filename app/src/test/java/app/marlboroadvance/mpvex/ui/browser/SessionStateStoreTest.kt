package app.marlboroadvance.mpvex.ui.browser

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateStoreTest {
  @Test fun systemKillsResumeTheSession() {
    assertTrue(SessionStateStore.shouldRestoreForExitReason(ApplicationExitInfo.REASON_LOW_MEMORY))
    assertTrue(SessionStateStore.shouldRestoreForExitReason(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
    // OEM memory managers (UMMS on Xiaomi) are reported through REASON_OTHER.
    assertTrue(SessionStateStore.shouldRestoreForExitReason(ApplicationExitInfo.REASON_OTHER))
  }

  @Test fun manualAndIncidentalExitsStartFresh() {
    assertFalse(SessionStateStore.shouldRestoreForExitReason(ApplicationExitInfo.REASON_USER_REQUESTED))
    assertFalse(SessionStateStore.shouldRestoreForExitReason(ApplicationExitInfo.REASON_USER_STOPPED))
    assertFalse(SessionStateStore.shouldRestoreForExitReason(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    assertFalse(SessionStateStore.shouldRestoreForExitReason(ApplicationExitInfo.REASON_CRASH))
    assertFalse(SessionStateStore.shouldRestoreForExitReason(ApplicationExitInfo.REASON_SIGNALED))
  }
}
