package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.ProxyRequestRegistry
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.ProxyRequestScope
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ProxyRequestScopeTest {
  @Test fun closingOneResponsePreservesOtherReadsAndRetirementClosesTransportOnce() {
    val registry = ProxyRequestRegistry()
    var closes = 0
    val first = registry.open()
    val second = registry.open()
    val body = second.own(object : ByteArrayInputStream(byteArrayOf(7)) {
      override fun close() { closes++ }
    })
    first.close()
    assertEquals(7, body.read())
    registry.cancelActive()
    assertEquals(1, closes)
    body.close()
    assertEquals(1, closes)
    try { body.read(); fail("Read after retirement") } catch (_: IOException) { }
  }

  @Test fun cancellationRunsOnceAndLateCallsAreCancelledImmediately() {
    val scope = ProxyRequestScope()
    var cancelled = 0
    scope.onCancel { cancelled++ }
    val detach = scope.onCancel { cancelled += 100 }
    detach()
    scope.close()
    scope.close()
    assertEquals(1, cancelled)
    scope.onCancel { cancelled++ }
    assertEquals(2, cancelled)
    try { scope.own(ByteArrayInputStream(byteArrayOf(1))).read(); fail("Cancelled read") } catch (_: IOException) { }
  }

  @Test fun oldResponsesStopButOnlyRetirementRejectsFutureRequests() {
    val registry = ProxyRequestRegistry()
    val old = registry.open()
    registry.cancelActive()
    try { old.checkActive(); fail("Old request survived") } catch (_: IOException) { }
    val next = registry.open()
    next.checkActive()
    registry.close()
    try { next.checkActive(); fail("Retired request survived") } catch (_: IOException) { }
    try { registry.open(); fail("Retired stream reopened") } catch (_: IOException) { }
  }

  @Test fun onlyAnExplicitPlaybackReplacementCancelsPreviousResponses() {
    val registry = ProxyRequestRegistry()
    val first = registry.open()
    val second = registry.open()
    first.checkActive()
    second.checkActive()
    val replacement = registry.open(replaceActive = true)
    for (old in listOf(first, second)) {
      try { old.checkActive(); fail("Replaced request survived") } catch (_: IOException) { }
    }
    replacement.checkActive()
    registry.close()
  }
  @Test fun retirementRejectsRetriesBeforeCancellationCallbacksFinish() {
    val registry = ProxyRequestRegistry()
    val active = registry.open()
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    active.onCancel { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
    val retire = Thread { registry.close() }.also { it.start() }
    try {
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
      repeat(3) {
        try { registry.open(); fail("Late native retry reopened a cancelled probe") } catch (_: IOException) { }
      }
    } finally {
      release.countDown()
      retire.join(5000)
    }
    assertFalse(retire.isAlive)
    registry.close()
  }

}
