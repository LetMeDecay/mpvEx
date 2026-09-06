package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionTest {
  private fun loaded(path: String = "http://127.0.0.1:8080/stream/a.mp4") = PlaybackSession().apply {
    begin(path)
    assertTrue(fileLoaded(path))
  }

  @Test
  fun delayedHistoryCannotOverrideUserInteraction() = runBlocking {
    val session = loaded()
    val lookup = CompletableDeferred<Unit>()
    val ticket = session.ticket()
    val restore = async {
      lookup.await()
      session.isCurrent(ticket)
    }
    session.interact() // Finger down must invalidate restoration before finger up.
    lookup.complete(Unit)
    assertFalse(restore.await())
  }

  @Test
  fun switchingFilesRejectsOldLoadAndSeekEvenWhenReturningToSamePath() {
    val session = loaded("a")
    val oldSeek = session.seek(120f, 0)
    session.begin("b")
    assertFalse(session.loaded)
    assertFalse(session.fileLoaded("a"))
    assertEquals(0f, session.displayPosition(120f, false, 1), 0f)
    assertFalse(session.isCurrent(oldSeek))
    assertTrue(session.fileLoaded("b"))
    session.begin("a")
    assertTrue(session.fileLoaded("a"))
    assertFalse(session.isCurrent(oldSeek))
  }

  @Test
  fun queuedEndOrLoadEventCannotActOnALaterVisitToTheSameFile() {
    val session = loaded("a")
    val oldGeneration = session.generation
    assertTrue(session.acceptsEvent("a", oldGeneration))
    session.begin("b")
    session.fileLoaded("b")
    session.begin("a")
    session.fileLoaded("a")
    assertFalse(session.acceptsEvent("a", oldGeneration))
    assertTrue(session.acceptsEvent("a", session.generation))
  }

  @Test
  fun onlyLatestQueuedSeekCanExecute() {
    val session = loaded()
    val first = session.seek(120f, 0)
    val second = session.seek(240f, 10)
    assertFalse(session.isCurrent(first))
    assertTrue(session.isCurrent(second))
    assertEquals(240f, session.displayPosition(120f, false, 20), 0f)
  }

  @Test
  fun pendingSeekDoesNotBounceToOldOrMissingSamples() {
    val session = loaded()
    session.seek(180f, 0)
    assertEquals(180f, session.displayPosition(20f, false, 1), 0f)
    assertEquals(180f, session.displayPosition(null, true, 100), 0f)
    assertEquals(180f, session.displayPosition(179.8f, true, 200), 0f)
    assertEquals(180.2f, session.displayPosition(180.2f, false, 300), 0f)
    assertEquals(181f, session.displayPosition(181f, false, 400), 0f)
  }

  @Test
  fun rejectedSeekEventuallyReturnsToActualPosition() {
    val session = loaded()
    session.seek(180f, 50)
    assertEquals(180f, session.displayPosition(20f, false, 10049), 0f)
    assertEquals(20f, session.displayPosition(20f, false, 10050), 0f)
  }

  @Test
  fun keyframeSeekShowsActualPositionInsteadOfPretendingToLandExactly() {
    val session = loaded()
    session.seek(180f, 0, showTarget = false)
    assertEquals(175f, session.displayPosition(175f, false, 100), 0f)
  }

  @Test
  fun newFileAndUnavailableSamplesClearPreviousTimeline() {
    val session = loaded("file:///storage/video.mp4")
    assertTrue(session.acceptsPath("/storage/video.mp4"))
    session.seek(180f, 0)
    session.begin("next")
    assertTrue(session.fileLoaded("next"))
    assertEquals(0f, session.displayPosition(null, false, 100), 0f)
    assertEquals(0f, session.displayPosition(Float.NaN, false, 100), 0f)
    assertEquals(0f, session.displayPosition(-1f, false, 100), 0f)
  }
}
