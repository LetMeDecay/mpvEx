package app.marlboroadvance.mpvex.ui.player

/** Accessed by the serial command worker; tracks executed choices, not queued intent. */
internal class StatisticsPageController(initialPage: Int) {
  private var appliedPage = initialPage

  fun apply(page: Int, command: (List<String>) -> Unit) {
    require(page in 0..5)
    if ((page == 0) != (appliedPage == 0)) {
      command(listOf("script-binding", "stats/display-stats-toggle"))
      appliedPage = if (page == 0) 0 else 1
    }
    if (page != 0) command(listOf("script-binding", "stats/display-page-$page"))
    appliedPage = page
  }
}
