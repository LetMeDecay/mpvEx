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
    state.invalidate(first) { cache.remove(first) }
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
    state.invalidate(first) {}
    val before = state.token(first)
    state.clear {}
    assertFalse(state.publish(first, before) { fail("old result published") })
    assertFalse(state.publish(other, 0) { fail("old result published") })
    val after = state.token(first)
    state.invalidate(first) {}
    assertTrue(state.token(first) > after)
    assertEquals(3L, state.changes.value)
  }
}
