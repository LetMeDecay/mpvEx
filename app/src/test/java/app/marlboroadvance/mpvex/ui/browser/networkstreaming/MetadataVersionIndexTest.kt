package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.*
import org.junit.Test

class MetadataVersionIndexTest {
  @Test fun versionsAreIsolatedByExactConnectionAndPath() {
    val index = MetadataVersionIndex()
    val first = "1::/a.mp4::100::::1"
    val newer = "1::/a.mp4::101::etag::2"
    val prefixCollision = "1::/a.mp4.more::100::::1"
    val other = "11::/a.mp4::100::::1"
    listOf(first, newer, prefixCollision, other).forEach(index::add)
    assertEquals(setOf(first, newer), index.keysFor(1, "/a.mp4"))
    assertEquals(setOf(first, newer, prefixCollision), index.keysForConnection(1))
    index.remove(first)
    assertEquals(setOf(newer), index.keysFor(1, "/a.mp4"))
    index.clear()
    assertTrue(index.keysForConnection(1).isEmpty())
  }

  @Test fun legacyDelimitersRemainDiscoverableWithoutChangingCacheIdentity() {
    val index = MetadataVersionIndex()
    val pathWithDelimiter = "1::/a::b.mp4::100::::1"
    val etagWithDelimiter = "1::/c.mp4::100::etag::part::1"
    index.add(pathWithDelimiter)
    index.add(etagWithDelimiter)
    assertEquals(setOf(pathWithDelimiter), index.keysFor(1, "/a::b.mp4"))
    assertEquals(setOf(etagWithDelimiter), index.keysFor(1, "/c.mp4"))
    index.remove(pathWithDelimiter)
    assertTrue(index.keysFor(1, "/a::b.mp4").isEmpty())
  }
}
