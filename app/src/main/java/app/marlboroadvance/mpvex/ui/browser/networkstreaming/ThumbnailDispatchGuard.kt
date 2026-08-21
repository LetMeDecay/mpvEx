package app.marlboroadvance.mpvex.ui.browser.networkstreaming

/**
 * Snapshot used while the dispatcher transfers a queued request to its sole
 * native consumer. The caller must build and evaluate this snapshot while
 * holding the dispatcher's lock; it is intentionally pure so the start gate
 * can be tested without loading the native player.
 */
internal data class ThumbnailDispatchSnapshot(
  val waiters: Int,
  val resultCancelled: Boolean,
  val stillRegistered: Boolean,
)

internal object ThumbnailDispatchGuard {
  fun canStart(snapshot: ThumbnailDispatchSnapshot): Boolean =
    snapshot.waiters > 0 && !snapshot.resultCancelled && snapshot.stillRegistered
}
