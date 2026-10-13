package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVLib

/**
 * Transitional native access boundary for application-owned playback calls.
 *
 * Delegation deliberately preserves caller-thread execution, exceptions and
 * native return values. Instance/Surface ownership still belongs to MPVView;
 * this is not yet the serialized PlaybackEngine described in REFACTOR_PLAN.md.
 * Lazy getters also preserve MPVLib initialization timing.
 */
internal object MpvGateway {
  val propBoolean get() = MPVLib.propBoolean
  val propInt get() = MPVLib.propInt
  val propNode get() = MPVLib.propNode
  val propFloat get() = MPVLib.propFloat
  val propDouble get() = MPVLib.propDouble
  val propString get() = MPVLib.propString

  fun command(vararg arguments: String) = MPVLib.command(*arguments)
  fun setOptionString(name: String, value: String) = MPVLib.setOptionString(name, value)

  fun getPropertyBoolean(name: String) = MPVLib.getPropertyBoolean(name)
  fun getPropertyDouble(name: String) = MPVLib.getPropertyDouble(name)
  fun getPropertyInt(name: String) = MPVLib.getPropertyInt(name)
  fun getPropertyString(name: String) = MPVLib.getPropertyString(name)
  fun getPropertyFloat(name: String) = MPVLib.getPropertyFloat(name)

  fun setPropertyBoolean(name: String, value: Boolean) = MPVLib.setPropertyBoolean(name, value)
  fun setPropertyDouble(name: String, value: Double) = MPVLib.setPropertyDouble(name, value)
  fun setPropertyFloat(name: String, value: Float) = MPVLib.setPropertyFloat(name, value)
  fun setPropertyInt(name: String, value: Int) = MPVLib.setPropertyInt(name, value)
  fun setPropertyString(name: String, value: String) = MPVLib.setPropertyString(name, value)

  fun observeProperty(name: String, format: Int) = MPVLib.observeProperty(name, format)
  fun addObserver(observer: MPVLib.EventObserver) = MPVLib.addObserver(observer)
  fun removeObserver(observer: MPVLib.EventObserver) = MPVLib.removeObserver(observer)
  fun grabThumbnail(size: Int) = MPVLib.grabThumbnail(size)
  fun destroy() = MPVLib.destroy()
}
