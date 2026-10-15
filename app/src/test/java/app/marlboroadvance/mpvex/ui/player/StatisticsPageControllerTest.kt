package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsPageControllerTest {
  @Test fun switchesPagesWithoutTogglingVisibilityAndClosesOnce() {
    val controller = StatisticsPageController(3)
    val calls = mutableListOf<List<String>>()
    listOf(5, 2, 0, 0).forEach { controller.apply(it) { args -> calls.add(args) } }
    assertEquals(listOf(
      listOf("script-binding", "stats/display-page-5"),
      listOf("script-binding", "stats/display-page-2"),
      listOf("script-binding", "stats/display-stats-toggle"),
    ), calls)
  }

  @Test fun pageFailureDoesNotRepeatSuccessfulVisibilityToggle() {
    val controller = StatisticsPageController(0)
    val calls = mutableListOf<List<String>>()
    runCatching { controller.apply(4) { args ->
      calls.add(args)
      if (args.last() == "stats/display-page-4") error("page failed")
    } }
    controller.apply(2) { calls.add(it) }
    assertEquals(1, calls.count { it.last() == "stats/display-stats-toggle" })
    assertEquals("stats/display-page-2", calls.last().last())
  }
}
