package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.ResourceLease
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ResourceLeaseTest {
  @Test fun partialSetupClosesInReverseOrderEvenIfOneCloseFails() {
    val lease = ResourceLease()
    val closed = mutableListOf<Int>()
    lease.own(AutoCloseable { closed.add(1) })
    lease.own(AutoCloseable { closed.add(2); error("close failed") })
    lease.own(AutoCloseable { closed.add(3) })
    lease.close(); lease.close()
    assertEquals(listOf(3, 2, 1), closed)
  }
  @Test fun cancellationRejectsAndClosesLateResource() {
    val lease = ResourceLease()
    lease.close()
    var closes = 0
    try { lease.own(AutoCloseable { closes++ }); fail("Retired lease accepted resource") }
    catch (_: IOException) { }
    assertEquals(1, closes)
  }
}
