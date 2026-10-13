package app.marlboroadvance.mpvex.utils.media

import org.junit.Assert.*
import org.junit.Test

class LocalMediaIdentityTest {
  @Test fun sameNameInDifferentDirectoriesNeverSharesHistory() {
    assertNotEquals(LocalMediaIdentity.forUri("file:///videos/a/movie.mkv"), LocalMediaIdentity.forUri("file:///videos/b/movie.mkv"))
  }
  @Test fun pathAndNormalizedFileUriShareIdentity() {
    assertEquals(LocalMediaIdentity.forUri("/videos/a movie.mkv"), LocalMediaIdentity.forUri("file:///videos/sub/../a%20movie.mkv"))
  }
  @Test fun documentIdentityIncludesProviderAndDocument() {
    val first = LocalMediaIdentity.forUri("content://provider/document/first")
    assertEquals(first, LocalMediaIdentity.forUri("content://provider/document/first"))
    assertNotEquals(first, LocalMediaIdentity.forUri("content://other/document/first"))
    assertNotEquals(first, LocalMediaIdentity.forUri("content://provider/document/second"))
    assertFalse(first.contains("provider"))
  }
}
