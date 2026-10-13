package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.*
import org.junit.Test

class TrackIdentityTest {
  @Test fun reorderedIdsRestoreByDescriptor() {
    val key = TrackIdentity.key(listOf("audio", "en", "Main"))
    val saved = TrackIdentity.capture(1, listOf(TrackIdentity.Track(1, key)))
    assertEquals(7, TrackIdentity.resolve(saved, listOf(TrackIdentity.Track(7, key))))
  }
  @Test fun missingLegacyAndAmbiguousDescriptorsDoNotGuess() {
    assertNull(TrackIdentity.resolve("", listOf(TrackIdentity.Track(1, "key"))))
    assertNull(TrackIdentity.resolve("missing", listOf(TrackIdentity.Track(1, "key"))))
    assertNull(TrackIdentity.resolve("key", listOf(TrackIdentity.Track(1, "key"), TrackIdentity.Track(2, "key"))))
    assertEquals(-1, TrackIdentity.resolve(TrackIdentity.capture(-1, emptyList()), emptyList()))
  }
  @Test fun fieldBoundariesCannotCollide() {
    assertNotEquals(TrackIdentity.key(listOf("ab", "c")), TrackIdentity.key(listOf("a", "bc")))
  }
}
