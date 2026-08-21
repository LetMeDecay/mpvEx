package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/** Shared UI/runtime bounds for duration and resolution preloading. */
internal object NetworkPreloadPolicy {
  const val MAX_THREADS = 6

  fun clampThreads(configured: Int): Int = configured.coerceIn(1, MAX_THREADS)
}
