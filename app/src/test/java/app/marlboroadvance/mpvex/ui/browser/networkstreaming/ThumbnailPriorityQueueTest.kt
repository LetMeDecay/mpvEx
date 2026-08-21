package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPriorityQueueTest {
  private enum class Priority { CURRENT, VISIBLE, PREFETCH, BACKGROUND }

  private fun queue() = ThumbnailPriorityQueue<String, Priority> { it.ordinal }

  @Test
  fun offeringExistingKeyPromotesWithoutAddingAnotherEntry() {
    val queue = queue()
    val first = queue.offer("video", Priority.PREFETCH)
    val second = queue.offer("video", Priority.VISIBLE)

    assertSame(first, second)
    assertEquals(1, queue.size())
    assertEquals(Priority.VISIBLE, queue.poll()!!.priority)
  }

  @Test
  fun currentAndVisibleEntriesRunBeforePrefetch() {
    val queue = queue()
    queue.offer("background", Priority.BACKGROUND)
    queue.offer("prefetch", Priority.PREFETCH)
    queue.offer("current", Priority.CURRENT)
    queue.offer("visible", Priority.VISIBLE)

    assertEquals("current", queue.poll()!!.key)
    assertEquals("visible", queue.poll()!!.key)
    assertEquals("prefetch", queue.poll()!!.key)
    assertEquals("background", queue.poll()!!.key)
  }

  @Test
  fun cancellationRemovesOnlyTheQueuedKey() {
    val queue = queue()
    queue.offer("keep", Priority.VISIBLE)
    queue.offer("cancel", Priority.PREFETCH)

    assertTrue(queue.cancel("cancel"))
    assertFalse(queue.cancel("cancel"))
    assertEquals(1, queue.size())
    assertEquals("keep", queue.poll()!!.key)
  }
}
