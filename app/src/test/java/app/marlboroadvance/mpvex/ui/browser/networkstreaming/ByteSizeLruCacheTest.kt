package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.*
import org.junit.Test

class ByteSizeLruCacheTest {
  @Test fun readsKeepFrequentlyUsedEntriesWithinBudget() {
    val cache = ByteSizeLruCache<String, ByteArray>(8) { it.size.toLong() }
    cache["a"] = ByteArray(4)
    cache["b"] = ByteArray(4)
    assertNotNull(cache["a"])
    cache["c"] = ByteArray(4)
    assertNull(cache["b"])
    assertNotNull(cache["a"])
    assertNotNull(cache["c"])
    assertEquals(8L, cache.totalBytes())
  }

  @Test fun replacementOversizeAndRemovalKeepAccurateAccounting() {
    val cache = ByteSizeLruCache<String, ByteArray>(8) { it.size.toLong() }
    cache["a"] = ByteArray(6)
    cache["a"] = ByteArray(2)
    assertEquals(2L, cache.totalBytes())
    cache["huge"] = ByteArray(9)
    assertEquals(setOf("a"), cache.keys)
    val heldByUi = cache["a"]!!
    cache.remove("a")
    assertEquals(0L, cache.totalBytes())
    assertEquals(2, heldByUi.size)
    cache["b"] = ByteArray(8)
    cache.clear()
    assertEquals(0L, cache.totalBytes())
    assertTrue(cache.keys.isEmpty())
  }
}
