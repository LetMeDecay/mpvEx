package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVLib

/** Native entry boundary. Destruction waits for leases without blocking callbacks. */
internal object MpvGateway {
  private val access = NativePlaybackAccess()
  fun open() = access.open()
  val propBoolean get() = MPVLib.propBoolean
  val propInt get() = MPVLib.propInt
  val propNode get() = MPVLib.propNode
  val propFloat get() = MPVLib.propFloat
  val propDouble get() = MPVLib.propDouble
  val propString get() = MPVLib.propString

  fun loadFile(path: String): Long? = access.call {
    checkNotNull(MPVLib.commandNode("loadfile", path)?.get("playlist_entry_id")?.asInt()) {
      "Native load did not return a playlist entry identity"
    }
  }

  fun playingEntryId(): Long? = access.call {
    val position = MPVLib.getPropertyInt("playlist-playing-pos") ?: return@call null
    if (position < 0) return@call null
    val entry = MPVLib.getPropertyNode("playlist/$position") ?: return@call null
    entry["id"]?.asInt().takeIf { entry["playing"]?.asBoolean() == true }
  }

  fun command(vararg arguments: String) = access.call { MPVLib.command(*arguments) }
  fun setOptionString(name: String, value: String) = access.call { MPVLib.setOptionString(name, value) }

  fun getPropertyBoolean(name: String) = access.call { MPVLib.getPropertyBoolean(name) }
  fun getPropertyDouble(name: String) = access.call { MPVLib.getPropertyDouble(name) }
  fun getPropertyInt(name: String) = access.call { MPVLib.getPropertyInt(name) }
  fun getPropertyString(name: String) = access.call { MPVLib.getPropertyString(name) }
  fun getPropertyFloat(name: String) = access.call { MPVLib.getPropertyFloat(name) }

  fun setPropertyBoolean(name: String, value: Boolean) = access.call { MPVLib.setPropertyBoolean(name, value) }
  fun setPropertyDouble(name: String, value: Double) = access.call { MPVLib.setPropertyDouble(name, value) }
  fun setPropertyFloat(name: String, value: Float) = access.call { MPVLib.setPropertyFloat(name, value) }
  fun setPropertyInt(name: String, value: Int) = access.call { MPVLib.setPropertyInt(name, value) }
  fun setPropertyString(name: String, value: String) = access.call { MPVLib.setPropertyString(name, value) }

  fun observeProperty(name: String, format: Int) = access.call { MPVLib.observeProperty(name, format) }
  fun addObserver(observer: MPVLib.EventObserver) = MPVLib.addObserver(observer)
  fun removeObserver(observer: MPVLib.EventObserver) = MPVLib.removeObserver(observer)
  fun grabThumbnail(size: Int) = access.call { MPVLib.grabThumbnail(size) }
  fun destroy() = access.close { MPVLib.destroy() }
}
