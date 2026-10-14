package app.marlboroadvance.mpvex.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackStateStoreTest {
  @Test
  fun metadataBelongsToItsGenerationAndCannotLeakAcrossReplacement() {
    val store = PlaybackStateStore()
    val tracks = mutableListOf(TrackNode(id = 1, type = "audio", lang = "en"))
    val metadata = PlaybackMetadata(tracks = tracks, chapters = listOf(ChapterNode(0f, "Opening")), audioId = 1, speed = 1.5f)
    store.begin(1)
    store.ready(1)
    store.sample(1, 10f, 100f, false, false, false, metadata)
    tracks.clear()
    assertEquals(1, store.state.value.metadata.tracks.size)
    assertEquals(1, store.state.value.metadata.audioId)
    store.begin(2)
    assertTrue(store.state.value.metadata.tracks.isEmpty())
    assertNull(store.state.value.metadata.speed)
    store.sample(1, 11f, 100f, false, false, false, metadata)
    assertTrue(store.state.value.metadata.chapters.isEmpty())
    store.ready(2)
    store.sample(2, 0f, null, true, false, false, PlaybackMetadata(speed = Float.NaN))
    assertNull(store.state.value.metadata.speed)
    store.stopping(3)
    store.sample(2, 12f, 100f, false, false, false, metadata)
    assertTrue(store.state.value.metadata.tracks.isEmpty())
  }

  @Test
  fun replacingFileResetsTimelineInTheSameSnapshotAsGeneration() {
    val store = PlaybackStateStore()
    store.begin(1)
    store.opening(1)
    store.sample(1, 80f, 200f, false, false, false)
    store.begin(2)
    val state = store.state.value
    assertEquals(2L, state.generation)
    assertEquals(PlaybackPhase.Preparing, state.phase)
    assertEquals(0f, state.position, 0f)
    assertNull(state.duration)
    store.sample(1, 90f, 200f, false, false, false)
    store.failed(1)
    assertEquals(state, store.state.value)
  }

  @Test
  fun invalidOrMissingDurationIsUnknownAndDoesNotReuseOldValue() {
    val store = PlaybackStateStore()
    store.begin(1)
    store.sample(1, 12f, 500f, false, false, false)
    store.sample(1, Float.NaN, null, true, false, true)
    assertEquals(0f, store.state.value.position, 0f)
    assertNull(store.state.value.duration)
    assertTrue(store.state.value.paused)
    assertTrue(store.state.value.buffering)
    store.sample(1, 1f, Float.POSITIVE_INFINITY, true, false, false)
    assertNull(store.state.value.duration)
    store.sample(1, 1f, 0f, true, false, false)
    assertNull(store.state.value.duration)
  }

  @Test
  fun retirementRejectsLateReadyAndSeekAndOldStopCannotResetNewFile() {
    val store = PlaybackStateStore()
    store.begin(1)
    store.sample(1, 10f, 100f, false, false, false)
    store.stopping(2)
    store.sample(1, 11f, 100f, false, false, false)
    store.seek(1, 40f)
    store.sample(2, 11f, 100f, false, false, false)
    assertEquals(PlaybackPhase.Stopping, store.state.value.phase)
    store.begin(3)
    store.stopped(2)
    assertEquals(PlaybackPhase.Preparing, store.state.value.phase)
    assertEquals(3L, store.state.value.generation)
  }

  @Test
  fun failedLoadCannotBecomeReadyFromLateSamplesAndNewLoadRecovers() {
    val store = PlaybackStateStore()
    store.begin(1)
    store.failed(1)
    store.opening(1)
    store.sample(1, 10f, 100f, false, false, false)
    assertEquals(PlaybackPhase.Failed, store.state.value.phase)
    store.ready(1) // Only a verified native FILE_LOADED/RESTART may recover this session.
    assertEquals(PlaybackPhase.Ready, store.state.value.phase)
    assertNull(store.state.value.duration)
    store.begin(2)
    store.sample(2, 0f, 20f, false, false, false)
    store.seek(2, 8f)
    assertEquals(8f, store.state.value.position, 0f)
    assertTrue(store.state.value.seeking)
    store.sample(2, 7f, 20f, false, false, false)
    assertFalse(store.state.value.seeking)
  }
}
