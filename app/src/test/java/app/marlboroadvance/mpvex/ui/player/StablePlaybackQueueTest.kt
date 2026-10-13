package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StablePlaybackQueueTest {
  @Test fun windowsAreBoundedAndRetainOriginalOrderAfterDatabaseReorder() = runBlocking {
    val queue = StablePlaybackQueue((0 until 10_000).toList())
    val page = queue.window(9980, 100) { ids ->
      assertEquals(100, ids.size)
      ids.reversed().associateWith { "file-$it" }
    }
    assertEquals(9900, page.offset)
    assertEquals("file-9900", page.items.first())
    assertEquals("file-9999", page.items.last())
    assertEquals("file-7", queue.item(7) { ids ->
      assertEquals(listOf(7), ids)
      mapOf(7 to "file-7")
    })
  }

  @Test fun missingEntryCannotShiftPlaybackToAnotherFile() = runBlocking {
    val queue = StablePlaybackQueue(listOf(1, 2, 3))
    try {
      queue.item(1) { mapOf(1 to "first", 3 to "third") }
      fail("Must not silently substitute a different item")
    } catch (_: IllegalStateException) { }
  }

  @Test fun smallAndEmptyQueuesHaveCorrectBounds() = runBlocking {
    val empty = StablePlaybackQueue<Int>(emptyList()).window<String>(0, 100) { error("No read needed") }
    assertEquals(emptyList<String>(), empty.items)
    val page = StablePlaybackQueue(listOf(9, 4)).window(1, 100) { it.associateWith(Int::toString) }
    assertEquals(0, page.offset)
    assertEquals(listOf("9", "4"), page.items)
  }
}
