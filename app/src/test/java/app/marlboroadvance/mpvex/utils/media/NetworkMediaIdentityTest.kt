package app.marlboroadvance.mpvex.utils.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NetworkMediaIdentityTest {
  @Test
  fun identifierIsStableForTheSameConnectionAndPath() {
    val first = NetworkMediaIdentity.forFile(42L, "/shows/episode-01.mkv")
    val second = NetworkMediaIdentity.forFile(42L, "/shows/episode-01.mkv")

    assertEquals(first, second)
    assertEquals("network_42_${"/shows/episode-01.mkv".hashCode()}", first)
  }

  @Test
  fun identifierSeparatesConnectionsAndPaths() {
    val path = "/shows/episode-01.mkv"
    val identifier = NetworkMediaIdentity.forFile(42L, path)

    assertNotEquals(identifier, NetworkMediaIdentity.forFile(43L, path))
    assertNotEquals(identifier, NetworkMediaIdentity.forFile(42L, "/shows/episode-02.mkv"))
  }
}
