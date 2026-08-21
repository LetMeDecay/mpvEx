package app.marlboroadvance.mpvex.utils.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPrefetchSupportTest {
  @Test
  fun completedPrefetchCanRunAgainAfterHeaderCacheEviction() {
    val tracker = InFlightPrefetchTracker()

    assertTrue(tracker.tryStart("network_1_2"))
    assertFalse(tracker.tryStart("network_1_2"))
    tracker.complete("network_1_2")
    assertTrue(tracker.tryStart("network_1_2"))
  }

  @Test
  fun playlistOrderFollowsShuffleAndRepeatRules() {
    assertEquals(2, PlaylistPrefetchOrder.nextIndex(1, 4, true, listOf(0, 1, 2, 3), false))
    assertNull(PlaylistPrefetchOrder.nextIndex(3, 4, true, listOf(0, 1, 2, 3), true))
    assertEquals(0, PlaylistPrefetchOrder.nextIndex(3, 4, false, emptyList(), true))
    assertNull(PlaylistPrefetchOrder.nextIndex(3, 4, false, emptyList(), false))
  }
}
