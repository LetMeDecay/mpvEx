package app.marlboroadvance.mpvex.utils.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionPolicyTest {
  @Test
  fun initialExplicitPositionIsKeptWhileStateIsPendingAndWhenStateIsMissing() {
    assertEquals(null, PlaybackPositionPolicy.positionBeforeStateLookup(true, 42))
    assertEquals(42, PlaybackPositionPolicy.positionWithoutSavedState(true, 42))
  }

  @Test
  fun laterFileIsClearedBeforeLookupAndStartsAtZeroWithoutState() {
    assertEquals(0, PlaybackPositionPolicy.positionBeforeStateLookup(false, 42))
    assertEquals(0, PlaybackPositionPolicy.positionWithoutSavedState(false, 42))
    assertEquals(0, PlaybackPositionPolicy.positionWithoutSavedState(false, null))
  }

  @Test
  fun savedStateUsesExistingRestorePolicy() {
    assertEquals(null, PlaybackPositionPolicy.positionToRestore(true, null))
    assertEquals(null, PlaybackPositionPolicy.positionToRestore(true, 0))
    assertEquals(null, PlaybackPositionPolicy.positionToRestore(false, 42))
    assertEquals(42, PlaybackPositionPolicy.positionToRestore(true, 42))
  }
}
