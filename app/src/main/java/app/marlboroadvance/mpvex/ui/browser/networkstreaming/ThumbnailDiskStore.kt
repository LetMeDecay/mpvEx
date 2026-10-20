package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Serialize file changes, but decode at most two immutable file snapshots outside the lock. */
internal class ThumbnailDiskStore(
  private val root: File,
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
  companion object {
    const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
  }

  private val mutex = Mutex()
  private val decoders = Semaphore(2)

  /**
   * Evicts the oldest previews once the cache exceeds its budget. Runs under the
   * same lock as writes, so it never deletes a file that is being published.
   */
  suspend fun trim(): Int = withContext(Dispatchers.IO) {
    mutex.withLock {
      val files = root.walkTopDown().filter { it.isFile }.toList()
      var total = files.sumOf { it.length() }
      if (total <= maxBytes) return@withLock 0
      val target = (maxBytes * 85 / 100)
      var removed = 0
      for (file in files.sortedBy { it.lastModified() }) {
        if (total <= target) break
        val size = file.length()
        if (file.delete()) {
          total -= size
          removed++
        }
      }
      removed
    }
  }

  suspend fun <T> transaction(block: Transaction.() -> T): T = withContext(Dispatchers.IO) {
    mutex.withLock { Transaction(root).block() }
  }

  suspend fun <T> decode(
    connectionId: Long,
    key: String,
    canRead: () -> Boolean,
    decode: (InputStream) -> T?,
  ): T? = withContext(Dispatchers.IO) {
    decoders.withPermit {
      var source: InputStream? = null
      try {
        transaction {
          if (canRead()) source = open(connectionId, key)
        }
        currentCoroutineContext().ensureActive()
        // Android keeps an open descriptor readable after unlink/atomic replacement.
        // Callers must recheck their generation before publishing the decoded result.
        source?.let(decode)
      } finally {
        source?.close()
      }
    }
  }

  suspend fun write(
    connectionId: Long,
    key: String,
    canPublish: () -> Boolean,
    encode: (File) -> Boolean,
  ): Boolean = withContext(Dispatchers.IO) {
    var pending: PendingWrite? = null
    try {
      transaction {
        if (canPublish()) pending = prepare(connectionId, key)
      }
      val files = pending ?: return@withContext false
      currentCoroutineContext().ensureActive()
      if (!encode(files.temporary)) return@withContext false
      currentCoroutineContext().ensureActive()
      transaction {
        if (!canPublish() || !files.temporary.isFile) false
        else {
          check(files.temporary.renameTo(files.target)) { "Unable to publish thumbnail" }
          true
        }
      }
    } finally {
      pending?.temporary?.delete()
    }
  }

  internal data class PendingWrite(val target: File, val temporary: File)

  class Transaction internal constructor(private val root: File) {
    private fun file(connectionId: Long, key: String): File {
      val hash = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
      return File(File(root, connectionId.toString()), "$hash.webp")
    }

    fun exists(connectionId: Long, key: String): Boolean = file(connectionId, key).isFile

    internal fun open(connectionId: Long, key: String): InputStream? {
      val source = file(connectionId, key)
      return if (source.isFile) source.inputStream() else null
    }

    internal fun prepare(connectionId: Long, key: String): PendingWrite {
      val target = file(connectionId, key)
      check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
      val temporary = File.createTempFile("preview-", ".tmp", target.parentFile)
      return PendingWrite(target, temporary)
    }

    fun remove(connectionId: Long, key: String) {
      val target = file(connectionId, key)
      check(!target.exists() || target.delete()) { "Unable to remove thumbnail" }
    }

    fun clearConnection(connectionId: Long) {
      check(File(root, connectionId.toString()).deleteRecursively()) { "Unable to clear connection thumbnails" }
    }

    fun clear() {
      check(root.deleteRecursively()) { "Unable to clear thumbnails" }
    }
  }
}
