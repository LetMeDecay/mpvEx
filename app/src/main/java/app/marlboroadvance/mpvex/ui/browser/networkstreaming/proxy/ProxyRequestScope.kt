package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Cancellation is explicit and can interrupt a Call even while a native caller blocks. */
internal class ProxyRequestScope(private val onClosed: () -> Unit = {}) : Closeable {
  private val lock = Any()
  private val cancellations = mutableSetOf<() -> Unit>()
  @Volatile private var closed = false

  fun checkActive() { if (closed) throw IOException("Proxy request cancelled") }

  fun onCancel(action: () -> Unit): () -> Unit {
    val runNow = synchronized(lock) { if (closed) true else { cancellations.add(action); false } }
    if (runNow) action()
    return { synchronized(lock) { cancellations.remove(action) }; Unit }
  }

  override fun close() {
    val callbacks = synchronized(lock) {
      if (closed) return
      closed = true
      cancellations.toList().also { cancellations.clear() }
    }
    callbacks.forEach { runCatching { it() } }
    onClosed()
  }

  fun own(stream: InputStream): InputStream = object : FilterInputStream(stream) {
    override fun read(): Int { checkActive(); return stream.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int { checkActive(); return stream.read(b, off, len) }
    override fun close() {
      this@ProxyRequestScope.close() // Cancel sockets before closing their response streams.
      stream.close()
    }
  }
}

internal class ProxyRequestRegistry : Closeable {
  private val lock = Any()
  private val requests = mutableSetOf<ProxyRequestScope>()
  private var retired = false

  fun open(replaceActive: Boolean = false): ProxyRequestScope {
    lateinit var scope: ProxyRequestScope
    scope = ProxyRequestScope { synchronized(lock) { requests.remove(scope) }; Unit }
    var old: List<ProxyRequestScope> = emptyList()
    val reject = synchronized(lock) {
      if (retired) true else {
        if (replaceActive) { old = requests.toList(); requests.clear() }
        requests.add(scope)
        false
      }
    }
    old.forEach { it.close() }
    if (reject) scope.close()
    scope.checkActive()
    return scope
  }

  fun cancelActive() {
    val old = synchronized(lock) { requests.toList().also { requests.clear() } }
    old.forEach { it.close() }
  }

  override fun close() {
    synchronized(lock) { retired = true }
    cancelActive()
  }
}
