package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeSegmentCacheTest {
  @Test
  fun containingSegmentServesAnInteriorRange() {
    val cache = RangeSegmentCache(maxSegmentBytes = 32, maxEntries = 4, maxTotalBytes = 128)
    cache.put("version-a", 100, 109, ByteArray(10) { it.toByte() })

    assertArrayEquals(byteArrayOf(3, 4, 5, 6), cache.get("version-a", 103, 106))
  }

  @Test
  fun versionIdentityDoesNotReuseBytesFromAnOlderObject() {
    val cache = RangeSegmentCache(maxSegmentBytes = 32, maxEntries = 4, maxTotalBytes = 128)
    cache.put("etag-old", 0, 3, byteArrayOf(1, 2, 3, 4))
    cache.put("etag-new", 0, 3, byteArrayOf(9, 8, 7, 6))

    assertArrayEquals(byteArrayOf(1, 2, 3, 4), cache.get("etag-old", 0, 3))
    assertArrayEquals(byteArrayOf(9, 8, 7, 6), cache.get("etag-new", 0, 3))
    assertNull(cache.get("missing-version", 0, 3))
  }

  @Test
  fun lruAndByteCapsEvictTheLeastRecentlyUsedSegments() {
    val cache = RangeSegmentCache(maxSegmentBytes = 8, maxEntries = 2, maxTotalBytes = 8)
    cache.put("a", 0, 3, byteArrayOf(0, 1, 2, 3))
    cache.put("b", 4, 7, byteArrayOf(4, 5, 6, 7))
    assertTrue(cache.get("a", 0, 0)!!.contentEquals(byteArrayOf(0)))
    cache.put("c", 8, 11, byteArrayOf(8, 9, 10, 11))

    assertEquals(2, cache.entryCount())
    assertEquals(8, cache.totalBytes())
    assertNull(cache.get("b", 4, 7))
    assertTrue(cache.get("a", 0, 3)!!.contentEquals(byteArrayOf(0, 1, 2, 3)))
    assertTrue(cache.get("c", 8, 11)!!.contentEquals(byteArrayOf(8, 9, 10, 11)))
  }

  @Test
  fun totalByteCapIsEnforcedEvenWhenEntryCapAllowsMore() {
    val cache = RangeSegmentCache(maxSegmentBytes = 8, maxEntries = 4, maxTotalBytes = 6)
    cache.put("a", 0, 3, byteArrayOf(0, 1, 2, 3))
    cache.put("b", 4, 7, byteArrayOf(4, 5, 6, 7))

    assertEquals(1, cache.entryCount())
    assertEquals(4, cache.totalBytes())
    assertNull(cache.get("a", 0, 3))
    assertArrayEquals(byteArrayOf(4, 5, 6, 7), cache.get("b", 4, 7))
  }
}
