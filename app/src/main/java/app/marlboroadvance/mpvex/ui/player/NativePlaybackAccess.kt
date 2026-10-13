package app.marlboroadvance.mpvex.ui.player

/** Native calls hold leases, never a monitor across JNI or an event callback. */
internal class NativePlaybackAccess {
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  private val monitor = Object()
  private var open = false
  private var calls = 0

  fun open() = synchronized(monitor) {
    if (open) return@synchronized
    check(calls == 0)
    open = true
  }

  fun <T> call(action: () -> T): T? {
    synchronized(monitor) {
      if (!open) return null
      calls++
    }
    try {
      return action()
    } finally {
      synchronized(monitor) {
        calls--
        if (calls == 0) monitor.notifyAll()
      }
    }
  }

  fun retire() = synchronized(monitor) { open = false }

  /** Lifecycle owner calls open/close serially; callbacks during close see null. */
  fun close(destroy: () -> Unit) {
    synchronized(monitor) {
      open = false
      while (calls != 0) monitor.wait()
    }
    destroy()
  }
}
