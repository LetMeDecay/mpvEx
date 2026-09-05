package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.*
import org.junit.Test

class PlaybackStreamOwnershipTest {
  @Test fun queueReplacementKeepsOldFileUntilActualTransition() {
    val ownership = PlaybackStreamOwnership()
    ownership.replaceQueue(setOf("old-current", "old-next"))
    ownership.replaceQueue(setOf("new-current", "new-next"))
    assertEquals(setOf("old-next"), ownership.releaseUnused("old-current"))
    assertEquals(setOf("old-current"), ownership.releaseUnused("new-current"))
    assertTrue(ownership.releaseUnused("new-next").isEmpty())
  }

  @Test fun sharedEntriesRemainAndUnownedPendingLaunchIsNeverReleased() {
    val ownership = PlaybackStreamOwnership()
    ownership.replaceQueue(setOf("a", "shared"))
    ownership.replaceQueue(setOf("shared", "b"))
    assertEquals(setOf("a"), ownership.releaseUnused("b"))
    assertEquals(setOf("shared", "b"), ownership.releaseAll())
    assertTrue(ownership.releaseAll().isEmpty())
  }

  @Test fun singleFileAndNonProxyReplacementReleasePreviousQueue() {
    val ownership = PlaybackStreamOwnership()
    ownership.replaceQueue(setOf("old"))
    ownership.remember("single")
    ownership.replaceQueue(emptySet())
    assertEquals(setOf("old"), ownership.releaseUnused("single"))
    assertEquals(setOf("single"), ownership.releaseUnused(null))
  }
}
