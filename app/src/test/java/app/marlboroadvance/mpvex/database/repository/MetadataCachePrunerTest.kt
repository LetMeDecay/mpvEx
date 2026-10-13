package app.marlboroadvance.mpvex.database.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MetadataCachePrunerTest {
  @Test fun retainedPathsNeverAppearInAnyDeletionChunk() = runBlocking {
    val retained = (0..2000).map { "keep-$it" }.toSet()
    val missing = (0..2000).map { "missing-$it" }
    val deleted = mutableListOf<String>()
    val count = pruneMissingMetadata(retained.toList() + missing, retained::contains) {
      assertTrue(it.size <= 999)
      assertTrue(it.none(retained::contains))
      deleted.addAll(it)
    }
    assertEquals(missing.size, count)
    assertEquals(missing, deleted)
  }
  @Test fun allPresentDoesNotDeleteAndEmptyDatabaseDoesNotClearNewRows() = runBlocking {
    assertEquals(0, pruneMissingMetadata(listOf("present"), { true }) { fail("Unexpected delete") })
    assertEquals(0, pruneMissingMetadata(emptyList(), { false }) { fail("Unexpected delete") })
  }
}
