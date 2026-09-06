package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import app.marlboroadvance.mpvex.ui.browser.networkstreaming.RangeSegmentCache
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Four bounded progressive downloads (at most 8 MiB), each with independent reader cursors. */
internal class SharedRangeReads(private val maxDownloads: Int = 4) {
  // Authentication and URL are used only in memory, never logged or persisted.
  data class Key(val identity: String, val url: String, val authorization: String?, val start: Long, val end: Long)

  private class Download(val size: Int) {
    val signal = Object()
    val bytes = ByteArray(size)
    val owner = ProxyRequestScope()
    var received = 0
    var ready = false
    var failure: IOException? = null
    var readers = 0 // Protected by the map lock.
    var finished = false
    var released = false
  }

  private val lock = Any()
  private val downloads = mutableMapOf<Key, Download>()
  private val slots = Semaphore(maxDownloads)
  private val workers = Executors.newFixedThreadPool(maxDownloads) { work ->
    Thread(work, "WebDAV range reader").apply { isDaemon = true }
  }

  internal fun retainedDownloads(): Int = maxDownloads - slots.availablePermits()

  fun open(
    key: Key,
    consumer: ProxyRequestScope,
    source: (ProxyRequestScope) -> InputStream,
    completed: (ByteArray) -> Unit,
  ): InputStream? {
    val size = key.end - key.start + 1
    require(size in 1..RangeSegmentCache.MAX_SEGMENT_BYTES.toLong())
    consumer.checkActive()
    var created = false
    val download = synchronized(lock) {
      val entry = downloads[key] ?: run {
        if (!slots.tryAcquire()) return null // No unbounded queue of download buffers.
        Download(size.toInt()).also { downloads[key] = it; created = true }
      }
      entry.readers++
      entry
    }

    fun releaseSlotIfUnused() {
      // Called under lock; cancelled workers keep their slot until actually stopped.
      if (download.readers == 0 && download.finished && !download.released) {
        download.released = true
        slots.release()
      }
    }

    val lease = object : InputStream() {
      private var position = 0
      @Volatile private var closed = false
      var unbind: (() -> Unit)? = null

      fun awaitHeaders() = synchronized(download.signal) {
        while (!download.ready && download.failure == null && !closed) download.signal.wait()
        if (closed) throw IOException("Shared reader cancelled")
        download.failure?.let { throw it }
      }

      override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
      }

      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        return synchronized(download.signal) {
          if (closed) throw IOException("Shared reader cancelled")
          if (len == 0) return@synchronized 0
          while (!closed && position == download.received && position < download.size && download.failure == null) {
            download.signal.wait()
          }
          if (closed) throw IOException("Shared reader cancelled")
          if (position == download.size) return@synchronized -1
          download.failure?.let { throw it }
          val count = minOf(len, download.received - position)
          download.bytes.copyInto(b, off, position, position + count)
          position += count
          count
        }
      }

      override fun close() {
        val last = synchronized(lock) {
          if (closed) return
          closed = true
          download.readers--
          if (download.readers == 0) downloads.remove(key, download)
          releaseSlotIfUnused()
          download.readers == 0
        }
        unbind?.invoke()
        synchronized(download.signal) { download.signal.notifyAll() }
        if (last) download.owner.close()
      }
    }
    lease.unbind = consumer.onCancel { lease.close() }
    if (created) {
      workers.execute {
        try {
          download.owner.checkActive()
          source(download.owner).use { input ->
            synchronized(download.signal) { download.ready = true; download.signal.notifyAll() }
            var position = 0
            while (position < download.size) {
              download.owner.checkActive()
              val count = input.read(download.bytes, position, minOf(32 * 1024, download.size - position))
              if (count <= 0) throw EOFException("Incomplete shared range")
              position += count
              synchronized(download.signal) { download.received = position; download.signal.notifyAll() }
            }
            completed(download.bytes)
          }
        } catch (_: Exception) {
          synchronized(download.signal) {
            download.failure = IOException("Shared range failed")
            download.signal.notifyAll()
          }
        } finally {
          download.owner.close()
          synchronized(lock) { download.finished = true; releaseSlotIfUnused() }
        }
      }
    }
    return try { lease.awaitHeaders(); lease } catch (e: Exception) { lease.close(); throw e }
  }
}
