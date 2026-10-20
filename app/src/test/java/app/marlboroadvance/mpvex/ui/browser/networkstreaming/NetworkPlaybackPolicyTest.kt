package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NetworkPlaybackPolicyTest {
  @Test fun playbackDefersNewMetadataUntilItStops() = runBlocking {
    withTimeout(5000) {
      val policy = NetworkPlaybackPolicy()
      policy.setPlayback("a")
      val pending = async(start = CoroutineStart.UNDISPATCHED) { policy.awaitMetadata(); true }
      assertFalse(pending.isCompleted)
      policy.setPlayback(null)
      assertTrue(pending.await())
    }
  }

  @Test fun retiringOldQueueDoesNotReleaseNewPlaybackAndWaitingWorkIsCancellable() = runBlocking {
    withTimeout(5000) {
      val policy = NetworkPlaybackPolicy()
      policy.setPlayback("a")
      policy.setPlayback("b")
      policy.retire(listOf("a"))
      val cancelled = async(start = CoroutineStart.UNDISPATCHED) { policy.awaitMetadata() }
      assertFalse(cancelled.isCompleted)
      cancelled.cancelAndJoin()
      val pending = async(start = CoroutineStart.UNDISPATCHED) { policy.awaitMetadata(); true }
      assertFalse(pending.isCompleted)
      policy.retire(listOf("b"))
      assertTrue(pending.await())
    }
  }

  @Test fun retainedEofReleasesMetadataAndReplayRestoresGateWithoutRevivingOldFiles() = runBlocking {
    withTimeout(5000) {
      val policy = NetworkPlaybackPolicy()
      policy.setPlayback("a")
      policy.setEnded("a", true)
      policy.awaitMetadata()
      policy.setEnded("a", false)
      val replay = async(start = CoroutineStart.UNDISPATCHED) { policy.awaitMetadata() }
      assertFalse(replay.isCompleted)
      policy.setPlayback("b") // Autoplay changes identity without releasing the gate.
      policy.setEnded("a", true)
      policy.setEnded("a", false)
      assertFalse(replay.isCompleted)
      policy.setEnded("b", true)
      replay.await()
      policy.retire(listOf("b"))
      policy.setEnded("b", false)
      policy.awaitMetadata()
    }
  }

}
