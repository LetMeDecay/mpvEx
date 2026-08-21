package app.marlboroadvance.mpvex.utils.media

/** Position restoration policy for a newly loaded media item. */
object PlaybackPositionPolicy {
  /**
   * Returns the position to show while a file's saved state is being queried.
   *
   * A later playlist item must not visibly inherit the previous MPV position.
   * An explicitly positioned initial launch is allowed to keep that position
   * while its database lookup is pending.
   */
  fun positionBeforeStateLookup(
    isInitialLoad: Boolean,
    intentPosition: Int?,
  ): Int? = if (!isInitialLoad || intentPosition == null) 0 else null

  /**
   * Returns the position for a file without a saved database state.
   * Explicit launch positions apply only to the initial file; playlist items
   * without state always start at zero.
   */
  fun positionWithoutSavedState(
    isInitialLoad: Boolean,
    intentPosition: Int?,
  ): Int = if (isInitialLoad) intentPosition ?: 0 else 0

  /** Returns null when the existing preference policy leaves the current position untouched. */
  fun positionToRestore(
    savePositionOnQuit: Boolean,
    savedPosition: Int?,
  ): Int? = savedPosition
    ?.takeIf { savePositionOnQuit && it != 0 }
    ?.coerceAtLeast(0)
}
