package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.*
import org.junit.Test

class MetadataCacheGenerationTest {
  private val first = "1::/a.mp4::100::::1"
  private val sibling = "1::/b.mp4::100::::1"
  private val other = "2::/a.mp4::100::::1"

  @Test fun fileRefreshRejectsOldResultButKeepsUnrelatedWork() {
    val state = MetadataCacheGeneration()
    val token = state.token(first)
    val cache = mutableMapOf(first to "old")
    state.invalidate(1, mapOf(first to "/a.mp4")) { cache.remove(first) }
    assertFalse(state.publish(first, token) { cache[first] = "stale" })
    assertFalse(cache.containsKey(first))
    assertTrue(state.publish(sibling, token) { cache[sibling] = "valid" })
    assertTrue(state.publish(first, state.token(first)) { cache[first] = "new" })
    assertEquals("new", cache[first])
  }

  @Test fun connectionClearInvalidatesAllItsFilesOnly() {
    val state = MetadataCacheGeneration()
    val token = state.token(first)
    state.invalidateConnection(1) {}
    assertFalse(state.publish(first, token) { fail("old result published") })
    assertFalse(state.publish(sibling, token) { fail("old result published") })
    assertTrue(state.publish(other, token) {})
  }

  @Test fun clearAllDoesNotReuseTokensAfterIndividualInvalidation() {
    val state = MetadataCacheGeneration()
    state.invalidate(1, mapOf(first to "/a.mp4")) {}
    val before = state.token(first)
    state.clear {}
    assertFalse(state.publish(first, before) { fail("old result published") })
    assertFalse(state.publish(other, 0) { fail("old result published") })
    val after = state.token(first)
    state.invalidate(1, mapOf(first to "/a.mp4")) {}
    assertTrue(state.token(first) > after)
    assertEquals(3L, state.changes.value.revision)
  }
  @Test fun conflatedChangesRetainEveryAffectedPathAndSkipOtherConnections() {
    val state = MetadataCacheGeneration()
    val seen = state.changes.value.revision
    state.invalidate(1, mapOf(first to "/a.mp4")) {}
    state.invalidate(2, mapOf(other to "/a.mp4")) {}
    state.invalidate(1, mapOf(sibling to "/b.mp4")) {}
    val latest = state.changes.value
    assertEquals(setOf("/a.mp4", "/b.mp4"), latest.pathsSince(seen, 1))
    assertEquals(setOf("/a.mp4"), latest.pathsSince(seen, 2))
    assertEquals(emptySet<String>(), latest.pathsSince(seen, 3))
    assertEquals(emptySet<String>(), latest.pathsSince(latest.revision, 1))
  }

  @Test fun folderBatchEmitsOneRevisionAndConnectionClearCoversEarlierFileChanges() {
    val state = MetadataCacheGeneration()
    val batch = (1..1000).associate { "1::/$it.mp4::100::::1" to "/$it.mp4" }
    state.invalidate(1, batch) {}
    assertEquals(1L, state.changes.value.revision)
    assertEquals(1000, state.changes.value.pathsSince(0, 1)!!.size)
    state.invalidateConnection(1) {}
    assertNull(state.changes.value.pathsSince(0, 1))
    assertTrue(state.changes.value.paths[1].isNullOrEmpty())
    assertEquals(emptySet<String>(), state.changes.value.pathsSince(0, 2))
    state.clear {}
    assertNull(state.changes.value.pathsSince(0, 2))
  }

}
