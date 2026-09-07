package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.webDavPath
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavPathTest {
  @Test fun rootAndRelativeBasesShareOneAuthorityBoundary() {
    for (base in listOf("", "/")) {
      for (file in listOf("movie.mkv", "/movie.mkv")) assertEquals("/movie.mkv", webDavPath(base, file))
      for (root in listOf("", "/")) assertEquals("/", webDavPath(base, root))
    }
    for (base in listOf("dav", "/dav", "dav/", "/dav/")) {
      for (file in listOf("folder/movie.mkv", "/folder/movie.mkv")) {
        assertEquals("/dav/folder/movie.mkv", webDavPath(base, file))
      }
      for (root in listOf("", "/")) assertEquals("/dav", webDavPath(base, root))
    }
  }
}
