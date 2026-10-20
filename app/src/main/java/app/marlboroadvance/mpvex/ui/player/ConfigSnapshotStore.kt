package app.marlboroadvance.mpvex.ui.player

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable, content-addressed directories. A failed refresh never publishes partial files. */
internal class ConfigSnapshotStore(private val root: File) {
  private val readers = mutableMapOf<String, Int>()

  class Lease internal constructor(val directory: File, val usedFallback: Boolean,
                                   private val release: () -> Unit) : Closeable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
  }

  @Synchronized
  fun acquire(populate: (File) -> Unit): Lease {
    check(root.isDirectory || root.mkdirs())
    val stage = File(root, ".stage-${UUID.randomUUID()}")
    check(stage.mkdir())
    var fallback = false
    val directory = try {
      populate(stage)
      val digest = MessageDigest.getInstance("SHA-256")
      stage.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(stage).invariantSeparatorsPath }
        .forEach { file ->
          digest.update(file.relativeTo(stage).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
          digest.update(0.toByte())
          // Hash each file separately so file boundaries are unambiguous.
          val contents = MessageDigest.getInstance("SHA-256")
          file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
              val n = input.read(buffer)
              if (n < 0) break
              contents.update(buffer, 0, n)
            }
          }
          digest.update(contents.digest())
        }
      val version = digest.digest().joinToString("") { "%02x".format(it) }
      val target = File(root, version)
      if (!target.exists()) Files.move(stage.toPath(), target.toPath(), ATOMIC_MOVE)
      val pointer = File(root, ".pointer-${UUID.randomUUID()}")
      try {
        pointer.writeText(version)
        Files.move(pointer.toPath(), File(root, "current").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
      } finally { pointer.delete() }
      target
    } catch (cancelled: java.util.concurrent.CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      fallback = true
      current() ?: throw failure
    } finally { stage.deleteRecursively() }
    val name = directory.name
    readers[name] = (readers[name] ?: 0) + 1
    prune()
    return Lease(directory, fallback) { release(name) }
  }

  private fun current(): File? {
    val name = File(root, "current").takeIf { it.isFile }?.readText() ?: return null
    if (!name.matches(DIRECTORY_NAME)) return null
    return File(root, name).takeIf { it.isDirectory }
  }

  /**
   * Reuses the snapshot published for an identical input fingerprint without
   * rebuilding (and re-hashing) the whole directory. Callers must only pass a
   * fingerprint that covers every input the populate step would copy.
   */
  @Synchronized
  fun reuse(fingerprint: String): Lease? {
    val record = File(root, FINGERPRINT_FILE).takeIf { it.isFile }?.readLines() ?: return null
    if (record.size != 2 || record[0] != fingerprint || !record[1].matches(DIRECTORY_NAME)) return null
    val directory = File(root, record[1]).takeIf { it.isDirectory } ?: return null
    val name = directory.name
    readers[name] = (readers[name] ?: 0) + 1
    return Lease(directory, false) { release(name) }
  }

  /** Records which fingerprint the currently published snapshot represents. */
  @Synchronized
  fun remember(fingerprint: String, directory: File) {
    if (!directory.name.matches(DIRECTORY_NAME)) return
    val record = File(root, ".fingerprint-${UUID.randomUUID()}")
    try {
      record.writeText(fingerprint + "\n" + directory.name)
      Files.move(record.toPath(), File(root, FINGERPRINT_FILE).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
      record.delete()
    }
  }

  @Synchronized
  private fun release(name: String) {
    val count = readers[name] ?: return
    if (count == 1) readers.remove(name) else readers[name] = count - 1
    prune()
  }

  private fun prune() {
    val current = current()?.name
    root.listFiles()?.filter { it.isDirectory && it.name.matches(DIRECTORY_NAME) }
      ?.filter { it.name != current && it.name !in readers }
      ?.forEach { it.deleteRecursively() }
  }

  private companion object {
    const val FINGERPRINT_FILE = "fingerprint"
    val DIRECTORY_NAME = Regex("[0-9a-f]{64}")
  }
}
