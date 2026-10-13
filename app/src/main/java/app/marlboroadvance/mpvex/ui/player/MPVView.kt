package app.marlboroadvance.mpvex.ui.player

import android.content.Context
import android.os.Environment
import android.util.AttributeSet

import android.view.KeyCharacterMap
import android.view.KeyEvent
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import app.marlboroadvance.mpvex.ui.player.PlayerActivity.Companion.TAG
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.toColorHexString
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPVLib
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.reflect.KProperty

class MPVView(
  context: Context,
  attributes: AttributeSet,
) : BaseMPVView(context, attributes),
  KoinComponent {
  private val audioPreferences: AudioPreferences by inject()
  private val playerPreferences: PlayerPreferences by inject()
  private val decoderPreferences: DecoderPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val anime4kManager: Anime4KManager by inject()

  var isExiting = false

  fun getVideoOutAspect(): Double? {
    // Try to get aspect from video-params/aspect first
    val rawAspect = MpvGateway.getPropertyDouble("video-params/aspect")
    val rotate = MpvGateway.getPropertyInt("video-params/rotate") ?: 0

    // If aspect is not available or 0, calculate from width and height
    val finalAspect = if (rawAspect == null || rawAspect < 0.001) {
      val width = runCatching {
        MpvGateway.getPropertyInt("width") ?: MpvGateway.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MpvGateway.getPropertyInt("height") ?: MpvGateway.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      if (width > 0 && height > 0) {
        width.toDouble() / height.toDouble()
      } else {
        null
      }
    } else {
      rawAspect
    }

    return finalAspect?.let { aspect ->
      if (aspect <= 0.001) {
        return null
      }
      val isRotated = (rotate % 180 == 90)
      val correctedAspect = if (isRotated) 1.0 / aspect else aspect
      correctedAspect
    }
  }

  class TrackDelegate(
    private val name: String,
  ) {
    operator fun getValue(
      thisRef: Any?,
      property: KProperty<*>,
    ): Int {
      val v = MpvGateway.getPropertyString(name)
      // we can get null here for "no" or other invalid value
      return v?.toIntOrNull() ?: -1
    }

    operator fun setValue(
      thisRef: Any?,
      property: KProperty<*>,
      value: Int,
    ) {
      if (value == -1) MpvGateway.setPropertyString(name, "no") else MpvGateway.setPropertyInt(name, value)
    }
  }

  var sid: Int by TrackDelegate("sid")
  var secondarySid: Int by TrackDelegate("secondary-sid")
  var aid: Int by TrackDelegate("aid")

  override fun initOptions() {
    val profile = decoderPreferences.profile.get()
    MpvGateway.setOptionString("profile", profile)
    setVo(if (decoderPreferences.gpuNext.get()) "gpu-next" else "gpu")
    
    // Set GPU API context (Vulkan or OpenGL)
    if (decoderPreferences.useVulkan.get()) {
      MpvGateway.setOptionString("gpu-context", "androidvk")
    }

    // Set hwdec with fallback order: HW+ (mediacodec) -> HW (mediacodec-copy) -> SW (no)
    MpvGateway.setOptionString(
      "hwdec",
      if (decoderPreferences.tryHWDecoding.get()) "mediacodec,mediacodec-copy,no" else "no",
    )
    MpvGateway.setOptionString("hwdec-codecs", "all")

    if (decoderPreferences.useYUV420P.get()) {
      MpvGateway.setOptionString("vf", "format=yuv420p")
    }
    
    // Cap demuxer cache for mobile to prevent memory issues
    val cacheMegs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) 64 else 32
    MpvGateway.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
    MpvGateway.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
    
    val logLevel = if (advancedPreferences.verboseLogging.get()) "v" else "warn"
    MpvGateway.setOptionString("msg-level", "all=$logLevel")

    MpvGateway.setPropertyBoolean("keep-open", true)
    MpvGateway.setPropertyBoolean("input-default-bindings", true)

    MpvGateway.setOptionString("tls-verify", "yes")
    MpvGateway.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")

    val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    screenshotDir.mkdirs()
    MpvGateway.setOptionString("screenshot-directory", screenshotDir.path)

    VideoFilters.entries.forEach {
      MpvGateway.setOptionString(it.mpvProperty, it.preference(decoderPreferences).get().toString())
    }

    MpvGateway.setOptionString("speed", playerPreferences.defaultSpeed.get().toString())
    MpvGateway.setOptionString("vd-lavc-film-grain", "cpu")

    val preciseSeek = playerPreferences.usePreciseSeeking.get()
    MpvGateway.setOptionString("hr-seek", if (preciseSeek) "yes" else "no")
    MpvGateway.setOptionString("hr-seek-framedrop", if (preciseSeek) "no" else "yes")

    // Anime4K shader initialization (MUST be in initOptions, not after file load!)
    applyAnime4KShaders()

    setupSubtitlesOptions()
    setupAudioOptions()
  }

  override fun observeProperties() {
    for ((name, format) in observedProps) MpvGateway.observeProperty(name, format)
  }

  override fun postInitOptions() {
    when (decoderPreferences.debanding.get()) {
      Debanding.None -> {}
      Debanding.CPU -> MpvGateway.command("vf", "add", "@deband:gradfun=radius=12")
      Debanding.GPU -> MpvGateway.setOptionString("deband", "yes")
    }

    advancedPreferences.enabledStatisticsPage.get().let {
      if (it != 0) {
        MpvGateway.command("script-binding", "stats/display-stats-toggle")
        MpvGateway.command("script-binding", "stats/display-page-$it")
      }
    }
  }

  @Suppress("ReturnCount", "DEPRECATION")
  fun onKey(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_MULTIPLE || KeyEvent.isModifierKey(event.keyCode)) {
      return false
    }

    var mapped = KeyMapping[event.keyCode]
    if (mapped == null) {
      // Fallback to produced glyph
      if (!event.isPrintingKey) {
        return false
      }

      val ch = event.unicodeChar
      if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0) {
        return false // dead key
      }
      mapped = ch.toChar().toString()
    }

    if (event.repeatCount > 0) {
      return true
    }

    val mod: MutableList<String> = mutableListOf()
    event.isShiftPressed && mod.add("shift")
    event.isCtrlPressed && mod.add("ctrl")
    event.isAltPressed && mod.add("alt")
    event.isMetaPressed && mod.add("meta")

    val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
    mod.add(mapped)
    MpvGateway.command(action, mod.joinToString("+"))

    return true
  }

  private val observedProps =
    mapOf(
      "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "video-params/aspect" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "video-params/w" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "video-params/h" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "user-data/mpvex/show_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/toggle_ui" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/show_panel" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/set_button_title" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/reset_button_title" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/toggle_button" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/seek_by" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/seek_to" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/seek_by_with_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/seek_to_with_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/mpvex/software_keyboard" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
    )

  private fun setupAudioOptions() {
    // Disable MPV's automatic audio selection
    // App will handle track selection manually via TrackSelector to respect user choices
    MpvGateway.setOptionString("alang", "")
    MpvGateway.setOptionString("audio-delay", (audioPreferences.defaultAudioDelay.get() / 1000.0).toString())
    MpvGateway.setOptionString("audio-pitch-correction", audioPreferences.audioPitchCorrection.get().toString())
    MpvGateway.setOptionString("volume-max", (audioPreferences.volumeBoostCap.get() + 100).toString())
    
    // Volume normalization using dynamic audio normalization filter
    if (audioPreferences.volumeNormalization.get()) {
      MpvGateway.setOptionString("af", "dynaudnorm")
    }
  }

  // Setup
  private fun setupSubtitlesOptions() {
    // Disable MPV's automatic subtitle selection
    // App will handle track selection manually via TrackSelector to respect user choices
    MpvGateway.setOptionString("slang", "")
    MpvGateway.setOptionString("sub-auto", "no")
    MpvGateway.setOptionString("sub-file-paths", "")
    MpvGateway.setOptionString("subs-fallback", "no")

    val fontsDirPath = "${context.filesDir.path}/fonts/"
    MpvGateway.setOptionString("sub-fonts-dir", fontsDirPath)
    
    // Delay and speed for both primary and secondary
    val subDelay = (subtitlesPreferences.defaultSubDelay.get() / 1000.0).toString()
    val subSpeed = subtitlesPreferences.defaultSubSpeed.get().toString()
    MpvGateway.setOptionString("sub-delay", subDelay)
    MpvGateway.setOptionString("sub-speed", subSpeed)
    MpvGateway.setOptionString("secondary-sub-delay", subDelay)
    MpvGateway.setOptionString("secondary-sub-speed", subSpeed)

    val preferredFont = subtitlesPreferences.font.get()
    if (preferredFont.isNotBlank()) {
      MpvGateway.setOptionString("sub-font", preferredFont)
      MpvGateway.setOptionString("secondary-sub-font", preferredFont)
    }
    // If blank, MPV uses its default font

    if (subtitlesPreferences.overrideAssSubs.get()) {
      MpvGateway.setOptionString("sub-ass-override", "force")
      MpvGateway.setOptionString("sub-ass-justify", "yes")
      MpvGateway.setOptionString("secondary-sub-ass-override", "force")
    } else {
      MpvGateway.setOptionString("sub-ass-override", "no")
      MpvGateway.setOptionString("secondary-sub-ass-override", "no")
    }

    // Typography and styling for both primary and secondary
    val fontSize = subtitlesPreferences.fontSize.get().toString()
    val bold = if (subtitlesPreferences.bold.get()) "yes" else "no"
    val italic = if (subtitlesPreferences.italic.get()) "yes" else "no"
    val justify = subtitlesPreferences.justification.get().value
    val textColor = subtitlesPreferences.textColor.get().toColorHexString()
    val backgroundColor = subtitlesPreferences.backgroundColor.get().toColorHexString()
    val borderColor = subtitlesPreferences.borderColor.get().toColorHexString()
    val borderSize = subtitlesPreferences.borderSize.get().toString()
    val borderStyle = subtitlesPreferences.borderStyle.get().value
    val shadowOffset = subtitlesPreferences.shadowOffset.get().toString()
    val subPos = subtitlesPreferences.subPos.get().toString()
    val subScale = subtitlesPreferences.subScale.get().toString()

    MpvGateway.setOptionString("sub-font-size", fontSize)
    MpvGateway.setOptionString("sub-bold", bold)
    MpvGateway.setOptionString("sub-italic", italic)
    MpvGateway.setOptionString("sub-justify", justify)
    MpvGateway.setOptionString("sub-color", textColor)
    MpvGateway.setOptionString("sub-back-color", backgroundColor)
    MpvGateway.setOptionString("sub-border-color", borderColor)
    MpvGateway.setOptionString("sub-border-size", borderSize)
    MpvGateway.setOptionString("sub-border-style", borderStyle)
    MpvGateway.setOptionString("sub-shadow-offset", shadowOffset)
    MpvGateway.setOptionString("sub-scale", subScale)
    MpvGateway.setOptionString("sub-pos", subPos)
    
    MpvGateway.setOptionString("secondary-sub-font-size", fontSize)
    MpvGateway.setOptionString("secondary-sub-bold", bold)
    MpvGateway.setOptionString("secondary-sub-italic", italic)
    MpvGateway.setOptionString("secondary-sub-justify", justify)
    MpvGateway.setOptionString("secondary-sub-color", textColor)
    MpvGateway.setOptionString("secondary-sub-back-color", backgroundColor)
    MpvGateway.setOptionString("secondary-sub-border-color", borderColor)
    MpvGateway.setOptionString("secondary-sub-border-size", borderSize)
    MpvGateway.setOptionString("secondary-sub-border-style", borderStyle)
    MpvGateway.setOptionString("secondary-sub-shadow-offset", shadowOffset)
    MpvGateway.setOptionString("secondary-sub-scale", subScale)
    // Position secondary subtitle at top (10) instead of bottom to avoid overlap with primary
    MpvGateway.setOptionString("secondary-sub-pos", "10")

    val scaleByWindow = if (subtitlesPreferences.scaleByWindow.get()) "yes" else "no"
    MpvGateway.setOptionString("sub-scale-by-window", scaleByWindow)
    MpvGateway.setOptionString("sub-use-margins", scaleByWindow)
    MpvGateway.setOptionString("secondary-sub-scale-by-window", scaleByWindow)
    MpvGateway.setOptionString("secondary-sub-use-margins", scaleByWindow)
  }


  fun applyAnime4KShaders() {
    runCatching {
      val enabled = decoderPreferences.enableAnime4K.get()
      if (!enabled) {
        return
      }
      
      // Anime4K requires the legacy GPU path unless gpu-next is running on Vulkan.
      val gpuNextActive = decoderPreferences.gpuNext.get()
      val useVulkan = decoderPreferences.useVulkan.get()
      if (gpuNextActive && !useVulkan) {
        return  // Abort shader loading to prevent incompatible state
      }
      
      // Initialize shader files if needed - THIS IS CRITICAL!
      if (!anime4kManager.initialize()) {
        return
      }
      
      // Get preferences
      val modeStr = decoderPreferences.anime4kMode.get()
      
      // Check if mode is OFF - if so, don't apply any shaders
      if (modeStr == "OFF") {
        return  // Exit early - user wants it OFF
      }
      
      // Parse user's selected mode
      val mode = try {
          Anime4KManager.Mode.valueOf(modeStr)
      } catch (e: IllegalArgumentException) {
          Anime4KManager.Mode.OFF
      }
      
      val qualityStr = decoderPreferences.anime4kQuality.get()
      val quality = try {
        Anime4KManager.Quality.valueOf(qualityStr)
      } catch (e: IllegalArgumentException) {
        Anime4KManager.Quality.BALANCED
      }
      
      // Get shader chain from manager
      val shaderChain = anime4kManager.getShaderChain(mode, quality)
      
      if (shaderChain.isNotEmpty()) {
        // OpenGL-only tuning should not be pushed onto the Vulkan backend.
        if (!useVulkan) {
          MpvGateway.setOptionString("opengl-pbo", "yes")
          MpvGateway.setOptionString("opengl-early-flush", "no")
        }
        MpvGateway.setOptionString("vd-lavc-dr", "yes")
        
        // Apply shaders (MUST use setOptionString in initOptions!)
        MpvGateway.setOptionString("glsl-shaders", shaderChain)
      }
    }.onFailure {
      // Don't crash - just continue without shaders
    }
  }
}
