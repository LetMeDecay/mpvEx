package app.marlboroadvance.mpvex.utils.sort

import app.marlboroadvance.mpvex.domain.network.NetworkFile
import app.marlboroadvance.mpvex.preferences.NetworkSortType
import app.marlboroadvance.mpvex.preferences.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class SortUtilsTest {
  private val files = listOf(
    NetworkFile("unknown-b.mkv", "/unknown-b.mkv", 0L, false, duration = 0L),
    NetworkFile("known-short.mkv", "/known-short.mkv", 0L, false, duration = 10_000L),
    NetworkFile("unknown-a.mkv", "/unknown-a.mkv", 0L, false, duration = -1L),
    NetworkFile("known-long.mkv", "/known-long.mkv", 0L, false, duration = 20_000L),
  )

  @Test
  fun unknownDurationsStayLastWhenAscending() {
    val sorted = SortUtils.sortNetworkFiles(files, NetworkSortType.Duration, SortOrder.Ascending)

    assertEquals(
      listOf("known-short.mkv", "known-long.mkv", "unknown-a.mkv", "unknown-b.mkv"),
      sorted.map { it.name },
    )
  }

  @Test
  fun unknownDurationsStayLastWhenDescending() {
    val sorted = SortUtils.sortNetworkFiles(files, NetworkSortType.Duration, SortOrder.Descending)

    assertEquals(
      listOf("known-long.mkv", "known-short.mkv", "unknown-b.mkv", "unknown-a.mkv"),
      sorted.map { it.name },
    )
  }
}
