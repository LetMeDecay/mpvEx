package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceLeasesTest {
  @Test
  fun commandReplyDoesNotCloseBorrowedDescriptorAndEndClosesOnce() {
    val leases = PlaybackSourceLeases()
    var closes = 0
    val source = PlayableSource("fd://7") { closes++ }
    assertEquals(12L, leases.load(source) { path ->
      assertEquals("fd://7", path)
      12L
    })
    assertEquals(0, closes)
    leases.started(12)
    assertEquals(0, closes)
    leases.ended(12)
    leases.ended(12)
    leases.closeAll()
    source.close()
    assertEquals(1, closes)
  }

  @Test
  fun endBeforeCommandReplyClosesResourceWithoutLeavingAnOrphan() {
    val leases = PlaybackSourceLeases()
    var closes = 0
    leases.load(PlayableSource("fd://8") { closes++ }) {
      leases.started(8)
      leases.ended(8)
      assertEquals(0, closes) // The load still owns the source until it returns.
      8L
    }
    assertEquals(1, closes)
    leases.closeAll()
    assertEquals(1, closes)
  }

  @Test
  fun failedNativeCommandReleasesResourceAndNextLoadStillWorks() {
    val leases = PlaybackSourceLeases()
    val closed = mutableListOf<String>()
    try {
      leases.load(PlayableSource("bad") { closed.add("bad") }) { error("load failed") }
      fail("Expected failure")
    } catch (_: IllegalStateException) {
      assertEquals(listOf("bad"), closed)
    }
    leases.load(PlayableSource("good") { closed.add("good") }) { 2 }
    leases.ended(2)
    assertEquals(listOf("bad", "good"), closed)
  }

  @Test
  fun laterStartReleasesSkippedLoadButNotAFutureQueuedLoad() {
    val leases = PlaybackSourceLeases()
    val closed = mutableListOf<String>()
    leases.load(PlayableSource("a") { closed.add("a") }) { 100 }
    leases.load(PlayableSource("b") { closed.add("b") }) { 5 }
    leases.load(PlayableSource("c") { closed.add("c") }) { 40 }
    // Submission order, not numeric native IDs, determines skipped loads.
    leases.started(5)
    assertEquals(listOf("a"), closed)
    leases.ended(100) // Late end must not close B or C.
    leases.started(40)
    assertEquals(listOf("a", "b"), closed)
    leases.closeAll()
    assertEquals(listOf("a", "b", "c"), closed)
  }

  @Test
  fun earlyStartAlsoRetiresSkippedEntryAndExtrasLiveUntilEngineCleanup() {
    val leases = PlaybackSourceLeases()
    val closed = mutableListOf<String>()
    leases.retainExtra(PlayableSource("subtitle") { closed.add("subtitle") })
    leases.load(PlayableSource("skipped") { closed.add("skipped") }) { 1 }
    leases.load(PlayableSource("next") { closed.add("next") }) {
      leases.started(2)
      2L
    }
    assertEquals(listOf("skipped"), closed)
    leases.ended(2)
    assertEquals(listOf("skipped", "next"), closed)
    leases.closeAll()
    leases.closeAll()
    assertEquals(listOf("skipped", "next", "subtitle"), closed)
  }
}
