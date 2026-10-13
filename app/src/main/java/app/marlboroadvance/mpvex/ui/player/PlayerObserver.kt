package app.marlboroadvance.mpvex.ui.player

import androidx.lifecycle.lifecycleScope
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.launch

class PlayerObserver(
  private val activity: PlayerActivity,
) : MPVLib.EventObserver {
  private val identities get() = activity.viewModel.playbackEvents

  private fun dispatch(verifyNative: Boolean = false, action: (PlaybackEventIdentity.Event) -> Unit) {
    if (activity.player.isExiting) return
    val event = identities.capture() ?: return
    activity.lifecycleScope.launch {
      // START_FILE/FILE_LOADED can arrive before commandNode returns its ID.
      // Suspending here never blocks the JNI event thread or the command worker.
      if (!identities.resolve(event) || activity.player.isExiting) return@launch
      if (verifyNative && MpvGateway.playingEntryId() != event.entryId) return@launch
      action(event)
    }
  }

  override fun eventProperty(property: String) = dispatch { activity.onObserverEvent(property) }

  override fun eventProperty(property: String, value: Long) =
    dispatch { activity.onObserverEvent(property, value) }

  override fun eventProperty(property: String, value: Boolean) = dispatch(property == "eof-reached") { event ->
    activity.onObserverEvent(property, value, event.generation, MpvGateway.getPropertyString("path"))
  }

  override fun eventProperty(property: String, value: String) =
    dispatch { activity.onObserverEvent(property, value) }

  override fun eventProperty(property: String, value: Double) =
    dispatch { activity.onObserverEvent(property, value) }

  override fun eventProperty(property: String, value: MPVNode) =
    dispatch { activity.onObserverEvent(property, value) }

  override fun event(eventId: Int, data: MPVNode) {
    if (activity.player.isExiting) return
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_START_FILE ->
        identities.start(data["playlist_entry_id"]?.asInt())
      MPVLib.MpvEvent.MPV_EVENT_END_FILE -> identities.end(
        data["playlist_entry_id"]?.asInt(),
        data["playlist_insert_id"]?.asInt(),
        data["playlist_insert_num_entries"]?.asInt() ?: 0L,
      )
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> dispatch(verifyNative = true) { event ->
        val path = MpvGateway.getPropertyString("path") ?: return@dispatch
        activity.viewModel.playbackSession.adoptNativePath(path, event.generation)
        activity.event(eventId, path, event.generation)
      }
    }
  }
}
