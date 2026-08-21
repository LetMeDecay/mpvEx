package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/** Exponential retry delays for metadata probes that returned no duration. */
object NetworkMetadataRetryPolicy {
  const val BASE_DELAY_MS = 5_000L
  const val MAX_DELAY_MS = 5 * 60_000L

  fun delayForFailure(failureCount: Int): Long {
    if (failureCount <= 0) return 0L
    val exponent = (failureCount - 1).coerceAtMost(6)
    return (BASE_DELAY_MS * (1L shl exponent)).coerceAtMost(MAX_DELAY_MS)
  }
}
