package app.marlboroadvance.mpvex.ui.player

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlaybackEventIdentityTest {
  @Test(timeout = 5000)
  fun eventBeforeCommandReturnWaitsForNativeIdentity() = runBlocking {
    val identities = PlaybackEventIdentity()
    val request = identities.begin(1)
    identities.start(9000000000L)
    val event = identities.capture()!!
    val result = async { identities.resolve(event) }
    yield()
    assertFalse(result.isCompleted)
    identities.bind(request, 9000000000L)
    assertTrue(result.await())
  }

  @Test(timeout = 5000)
  fun delayedEventCannotBorrowNewGenerationEvenWhenPathIsTheSame() = runBlocking {
    val identities = PlaybackEventIdentity()
    val a = identities.begin(1)
    identities.bind(a, 10)
    identities.start(10)
    val oldEvent = identities.capture()!!
    identities.bind(identities.begin(2), 11)
    val newA = identities.begin(3)
    identities.bind(newA, 12)
    // Callback for the first A arrives after the application has requested A again.
    val delayed = identities.capture()!!
    assertFalse(identities.resolve(delayed))
    assertFalse(identities.resolve(oldEvent))
    identities.start(12)
    val current = identities.capture()!!
    assertEquals(3L, current.generation)
    assertTrue(identities.resolve(current))
  }

  @Test(timeout = 5000)
  fun replacementFailureAndRetirementReleaseUnresolvedEvents() = runBlocking {
    val identities = PlaybackEventIdentity()
    val first = identities.begin(1)
    identities.start(1)
    val waiting = async { identities.resolve(identities.capture()!!) }
    yield()
    val second = identities.begin(2)
    assertFalse(waiting.await())
    identities.bind(first, 1) // Late result cannot resurrect a retired request.
    identities.start(2)
    val failed = identities.capture()!!
    identities.bind(second, null)
    assertFalse(identities.resolve(failed))
    val third = identities.begin(3)
    identities.start(3)
    val event = identities.capture()!!
    identities.retire()
    identities.bind(third, 3)
    assertFalse(identities.resolve(event))
    assertNull(identities.capture())
  }

  @Test(timeout = 5000)
  fun nestedRedirectsResolveBeforeLoadReplyWithoutExpandingPlaylist() = runBlocking {
    val identities = PlaybackEventIdentity()
    val request = identities.begin(1)
    identities.start(10)
    identities.end(10, 20, 1000000)
    identities.start(21)
    identities.end(21, 2000000, 2)
    identities.start(2000001)
    val event = identities.capture()!!
    identities.bind(request, 10)
    assertTrue(identities.resolve(event))
    identities.start(3000000)
    assertFalse(identities.resolve(identities.capture()!!))
  }

  @Test(timeout = 5000)
  fun lateEndDoesNotClearNewEntryAndNewRequestDropsRedirects() = runBlocking {
    val identities = PlaybackEventIdentity()
    identities.bind(identities.begin(1), 10)
    identities.start(10)
    identities.end(10, 20, 2)
    identities.start(20)
    identities.end(10, null, 0)
    assertTrue(identities.resolve(identities.capture()!!))
    identities.bind(identities.begin(2), 11)
    assertFalse(identities.resolve(identities.capture()!!))
    identities.end(20, null, 0)
    assertNull(identities.capture())
  }
}
