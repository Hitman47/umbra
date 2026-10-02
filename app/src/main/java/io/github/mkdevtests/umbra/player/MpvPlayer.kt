package io.github.mkdevtests.umbra.player

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns one libmpv instance and exposes its playback state as flows.
 *
 * Surface handling follows mpv-android's BaseMPVView: the video output is
 * only enabled while a Surface is attached, and the first file is loaded
 * once the Surface exists (mpv would crash rendering to no surface).
 *
 * mpv calls the observer from its own event thread; StateFlow is thread-safe,
 * so values are published from there directly.
 */
class MpvPlayer(context: Context) : MPVLib.EventObserver, SurfaceHolder.Callback {

    private val mpv = MPVLib.create(context.applicationContext)
        ?: error("libmpv could not be created")
    private var pendingFile: String? = null
    private var externalSubtitles: List<String> = emptyList()

    private val _position = MutableStateFlow(0.0)
    val position: StateFlow<Double> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _buffering = MutableStateFlow(true)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _audioLabel = MutableStateFlow("")
    val audioLabel: StateFlow<String> = _audioLabel.asStateFlow()

    private val _subtitleLabel = MutableStateFlow("")
    val subtitleLabel: StateFlow<String> = _subtitleLabel.asStateFlow()

    init {
        val cacheDir = context.cacheDir.absolutePath
        mpv.setOptionString("config", "no")
        mpv.setOptionString("gpu-shader-cache-dir", cacheDir)
        mpv.setOptionString("icc-cache-dir", cacheDir)

        // Render through the GPU so mpv applies its shaders: HDR / Dolby Vision
        // tone mapping and ASS subtitles. "mediacodec" hands decoded frames to
        // the GPU without a copy (AImageReader); "mediacodec-copy" is the
        // fallback. At 4K the copy alone made playback stutter.
        mpv.setOptionString("vo", VO)
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        // Cheap scalers, no dithering, no per-frame HDR peak detection:
        // the default quality settings drop frames on 4K HDR with a tablet GPU.
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")

        // TrueHD / DTS are decoded in software and downmixed for the device.
        mpv.setOptionString("ao", "audiotrack,opensles")

        // No fontconfig on Android: point libass at the system fonts.
        // Fonts embedded in MKV files (typical for anime ASS) still win.
        mpv.setOptionString("sub-fonts-dir", "/system/fonts")
        mpv.setOptionString("osd-fonts-dir", "/system/fonts")
        mpv.setOptionString("sub-font", "Roboto")

        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("demuxer-max-bytes", "64MiB")
        mpv.setOptionString("demuxer-max-back-bytes", "32MiB")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("input-default-bindings", "no")

        mpv.init()

        mpv.setOptionString("save-position-on-quit", "no")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("idle", "once")

        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("core-idle", MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("aid", MpvFormat.MPV_FORMAT_STRING)
        mpv.observeProperty("sid", MpvFormat.MPV_FORMAT_STRING)
    }

    /** Plays [url] as soon as a Surface is available, with extra subtitle files. */
    fun play(url: String, subtitles: List<String> = emptyList()) {
        pendingFile = url
        externalSubtitles = subtitles
    }

    fun togglePause() = mpv.command(arrayOf("cycle", "pause"))

    fun pause() = mpv.setPropertyBoolean("pause", true)

    fun seekTo(seconds: Double) = mpv.command(arrayOf("seek", seconds.toString(), "absolute"))

    fun seekBy(seconds: Int) = mpv.command(arrayOf("seek", seconds.toString(), "relative"))

    fun cycleAudio() = mpv.command(arrayOf("cycle", "aid"))

    fun cycleSubtitles() = mpv.command(arrayOf("cycle", "sid"))

    /** Technical summary for the debug overlay (decoder, HDR, drops). */
    fun debugInfo(): String {
        fun p(name: String) = mpv.getPropertyString(name) ?: "?"
        return buildString {
            appendLine("${p("video-codec")}  ${p("video-params/w")}x${p("video-params/h")}")
            appendLine("hwdec: ${p("hwdec-current")}  vo: ${p("current-vo")}")
            appendLine("gamma: ${p("video-params/gamma")}  primaries: ${p("video-params/primaries")}")
            appendLine("audio: ${p("audio-codec-name")}  ${p("audio-params/channel-count")} ch")
            append("fps: ${p("estimated-vf-fps")}  dropped: ${p("frame-drop-count")}  cache: ${p("demuxer-cache-duration")} s")
        }
    }

    fun release() {
        mpv.removeObserver(this)
        mpv.destroy()
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        mpv.attachSurface(holder.surface)
        mpv.setOptionString("force-window", "yes")
        val file = pendingFile
        if (file != null) {
            mpv.command(arrayOf("loadfile", file))
            pendingFile = null
        } else {
            mpv.setPropertyString("vo", VO)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mpv.setPropertyString("vo", "null")
        mpv.setPropertyString("force-window", "no")
        mpv.detachSurface()
    }

    // --- MPVLib.EventObserver (mpv event thread) ---

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {}

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> _position.value = value
            "duration" -> _duration.value = value
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> _paused.value = value
            "paused-for-cache" -> _buffering.value = value
            "core-idle" -> if (!value) _buffering.value = false
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "aid" -> _audioLabel.value = trackLabel("audio", value, off = "Aucune")
            "sid" -> _subtitleLabel.value = trackLabel("sub", value, off = "Désactivés")
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            // mpv only finds subtitles next to local files: add the NAS ones by hand.
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED ->
                externalSubtitles.forEach { mpv.command(arrayOf("sub-add", it, "auto")) }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> Log.i(TAG, "end of file")
        }
    }

    private fun trackLabel(type: String, id: String, off: String): String {
        if (id == "no") return off
        val base = "current-tracks/$type"
        val parts = listOfNotNull(
            mpv.getPropertyString("$base/lang"),
            mpv.getPropertyString("$base/title"),
            mpv.getPropertyString("$base/codec"),
        )
        return parts.joinToString(" · ").ifEmpty { "Piste $id" }
    }

    private companion object {
        const val TAG = "MpvPlayer"
        const val VO = "gpu-next"
    }
}
