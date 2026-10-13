package app.marlboroadvance.mpvex.ui.player

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ConfigSnapshotStoreTest {
  private fun withStore(test: (ConfigSnapshotStore, File) -> Unit) {
    val root = Files.createTempDirectory("config-snapshot-").toFile()
    try { test(ConfigSnapshotStore(root), root) } finally { root.deleteRecursively() }
  }

  @Test fun failedRefreshRetainsWholePreviousSnapshotAcrossStoreRecreation() = withStore { store, root ->
    val first = store.acquire { File(it, "mpv.conf").writeText("old"); File(it, "input.conf").writeText("keys") }
    val oldDirectory = first.directory
    first.close()
    val failed = ConfigSnapshotStore(root).acquire {
      File(it, "mpv.conf").writeText("partial")
      error("provider unavailable")
    }
    assertTrue(failed.usedFallback)
    assertEquals(oldDirectory, failed.directory)
    assertEquals("old", File(failed.directory, "mpv.conf").readText())
    assertEquals("keys", File(failed.directory, "input.conf").readText())
    assertFalse(root.listFiles()!!.any { it.name.startsWith(".stage") })
    failed.close()
  }

  @Test fun sameContentReusesDirectoryAndOldReaderSurvivesReplacement() = withStore { store, _ ->
    val first = store.acquire { File(it, "font.ttf").writeText("first") }
    val duplicate = store.acquire { File(it, "font.ttf").writeText("first") }
    assertEquals(first.directory, duplicate.directory)
    val next = store.acquire { File(it, "font.ttf").writeText("second") }
    assertNotEquals(first.directory, next.directory)
    assertEquals("first", File(first.directory, "font.ttf").readText())
    first.close(); first.close()
    assertTrue(duplicate.directory.exists())
    duplicate.close()
    assertFalse(first.directory.exists())
    next.close()
    assertTrue(next.directory.exists())
  }

  @Test fun deletionAndEmptyConfigurationArePublished() = withStore { store, _ ->
    val first = store.acquire { File(it, "mpv.conf").writeText("old"); File(it, "removed.ttf").writeText("font") }
    val next = store.acquire { File(it, "mpv.conf").writeText("") }
    assertFalse(File(next.directory, "removed.ttf").exists())
    assertEquals("", File(next.directory, "mpv.conf").readText())
    first.close(); next.close()
  }

  @Test fun cancellationIsNotConvertedIntoFallback() = withStore { store, root ->
    val first = store.acquire { File(it, "mpv.conf").writeText("old") }
    try {
      store.acquire { throw CancellationException() }
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { }
    assertTrue(first.directory.exists())
    assertFalse(root.listFiles()!!.any { it.name.startsWith(".stage") })
    first.close()
  }
}
