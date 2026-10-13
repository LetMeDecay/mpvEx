package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.domain.network.NetworkFile
import org.junit.Assert.*
import org.junit.Test

class NetworkFileVersionTest {
  @Test fun replacementCannotReusePathOnlyThumbnail() {
    val file = NetworkFile("movie", "/movie", 10, false, lastModified = 20, etag = "v1")
    assertTrue(file.sameVersion(file.copy(duration = 100)))
    assertFalse(file.sameVersion(file.copy(size = 11)))
    assertFalse(file.sameVersion(file.copy(lastModified = 21)))
    assertFalse(file.sameVersion(file.copy(etag = "v2")))
    assertFalse(file.sameVersion(file.copy(path = "/other")))
    assertFalse(file.sameVersion(null))
  }
}
