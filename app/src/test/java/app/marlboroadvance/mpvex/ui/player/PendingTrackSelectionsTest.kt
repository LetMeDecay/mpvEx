package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.*
import org.junit.Test

class PendingTrackSelectionsTest {
  @Test fun rapidInputUsesLastIntentUntilAcknowledged() {
    val choices = PendingTrackSelections()
    choices.choose(PlaybackTrack.Audio, 4, 0)
    assertEquals(4, choices.selected(PlaybackTrack.Audio, 1, 1))
    choices.choose(PlaybackTrack.Audio, -1, 2)
    choices.acknowledge(PlaybackTrack.Audio, 4)
    assertEquals(-1, choices.selected(PlaybackTrack.Audio, 4, 3))
    choices.acknowledge(PlaybackTrack.Audio, -1)
    assertEquals(7, choices.selected(PlaybackTrack.Audio, 7, 4))
  }
  @Test fun missingAcknowledgementExpiresAndFileChangeClearsAllTracks() {
    val choices = PendingTrackSelections()
    choices.choose(PlaybackTrack.Subtitle, 2, 100)
    assertEquals(2, choices.selected(PlaybackTrack.Subtitle, -1, 10099))
    assertEquals(-1, choices.selected(PlaybackTrack.Subtitle, -1, 10100))
    choices.choose(PlaybackTrack.SecondarySubtitle, 5, 11000)
    choices.clear()
    assertEquals(-1, choices.selected(PlaybackTrack.SecondarySubtitle, -1, 11001))
  }
}
