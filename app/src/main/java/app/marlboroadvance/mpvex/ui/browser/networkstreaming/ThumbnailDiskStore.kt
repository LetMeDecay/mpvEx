package app.marlboroadvance.mpvex.ui.browser.networkstreaming

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes disk changes on IO without holding the cache's in-memory generation lock. */
internal class ThumbnailDiskStore(private val root: File) {
  private val mutex = Mutex()

  suspend fun <T> transaction(block: Transaction.() -> T): T = withContext(Dispatchers.IO) {
    mutex.withLock { Transaction(root).block() }
  }

  class Transaction internal constructor(private val root: File) {
    private fun file(connectionId: Long, key: String): File {
      val hash = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }
      return File(File(root, connectionId.toString()), "$hash.webp")
    }

    fun exists(connectionId: Long, key: String): Boolean = file(connectionId, key).isFile

    fun <T> read(connectionId: Long, key: String, decode: (File) -> T?): T? {
      val source = file(connectionId, key)
      return if (source.isFile) decode(source) else null
    }

    /** Encode away from the live path; a failed/stale write never replaces a valid image. */
    fun write(
      connectionId: Long,
      key: String,
      canPublish: () -> Boolean,
      encode: (File) -> Boolean,
    ): Boolean {
      val target = file(connectionId, key)
      check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
      val temporary = File.createTempFile("preview-", ".tmp", target.parentFile)
      return try {
        if (!encode(temporary) || !canPublish()) false
        else {
          check(temporary.renameTo(target)) { "Unable to publish thumbnail" }
          true
        }
      } finally {
        temporary.delete()
      }
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
