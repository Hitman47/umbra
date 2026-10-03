package io.github.mkdevtests.umbra.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvFormat
import io.github.mkdevtests.umbra.settings.Language
import io.github.mkdevtests.umbra.settings.NO_SUBTITLES
import io.github.mkdevtests.umbra.settings.Settings
import io.github.mkdevtests.umbra.settings.Track
import io.github.mkdevtests.umbra.settings.chooseTracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Owns one libmpv instance and exposes its playback state as flows.
 *
 * Surface handling follows mpv-android's BaseMPVView: the video output is
 * only enabled while a Surface is attached, and the first file is loaded
 * once the Surface exists (mpv would crash rendering to no surface).
 *
 * mpv calls the observer from its own event thread; StateFlow is thread-safe,
 * so values are published from there directly.
 *
 * Audio and subtitle tracks follow the user's [settings] when a file opens.
 */
class MpvPlayer(context: Context, private val settings: Settings) : MPVLib.EventObserver, SurfaceHolder.Callback {

    private val mpv = MPVLib.create(context.applicationContext)
        ?: error("libmpv could not be created")
    private var pendingFile: String? = null
    private var surfaceAttached = false
    private var externalSubtitles: List<String> = emptyList()

    private val _position = MutableStateFlow(0.0)
    val position: StateFlow<Double> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _buffering = MutableStateFlow(true)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    val audioTracks: StateFlow<List<PlayerTrack>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<PlayerTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<PlayerTrack>> = _subtitleTracks.asStateFlow()

    private val _subtitleDelay = MutableStateFlow(0.0)
    val subtitleDelay: StateFlow<Double> = _subtitleDelay.asStateFlow()

    private val _speed = MutableStateFlow(1.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    /** Zoomed to fill the screen (cropping the edges) instead of showing the whole picture. */
    private val _fill = MutableStateFlow(false)
    val fill: StateFlow<Boolean> = _fill.asStateFlow()

    // What the viewer waited for, read by [figures] for the playback measures (ms, elapsedRealtime).
    @Volatile private var loadAt = 0L
    @Volatile private var firstFrameAt = 0L
    @Volatile private var openMs: Long? = null
    @Volatile private var loadedMs: Long? = null
    @Volatile private var started = false

    /** The file failed to open (bad address, refused account, unreadable): mpv gave up before any picture. */
    @Volatile var openFailed = false
        private set

    /** The first frame is shown. */
    val isOpen get() = openMs != null

    /** A seek is under way. */
    val isSeeking get() = seekAt != 0L
    @Volatile private var seekAt = 0L
    private val seeks = java.util.Collections.synchronizedList(mutableListOf<Long>())
    @Volatile private var stallAt = 0L
    @Volatile private var stalls = 0
    @Volatile private var stalledMs = 0L

    /** The file played to its end (mpv keeps the last frame: keep-open). */
    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended.asStateFlow()

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
        mpv.setOptionString("sub-scale", settings.subtitleSize.scale.toString())

        // Faster opening: ffmpeg probes 5 s of packets by default, 1 s is plenty for MKV/MP4.
        mpv.setOptionString("demuxer-lavf-analyzeduration", "1")
        // A connection cut mid-file (Wi-Fi roaming, NAS reconnect) resumes where it stopped.
        mpv.setOptionString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=4")
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
        mpv.observeProperty("track-list", MpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("sub-delay", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("speed", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
    }

    /** Plays [url] from [start] seconds with extra subtitle files: now, or as soon as a Surface is available. */
    fun play(url: String, subtitles: List<String> = emptyList(), start: Double = 0.0) {
        externalSubtitles = subtitles
        _ended.value = false
        _buffering.value = true
        // The previous file's values must not be saved as this one's progress.
        _position.value = 0.0
        _duration.value = 0.0
        loadAt = SystemClock.elapsedRealtime()
        firstFrameAt = 0L
        openMs = null
        loadedMs = null
        started = false
        openFailed = false
        seekAt = 0L
        seeks.clear()
        stallAt = 0L
        stalls = 0
        stalledMs = 0L
        mpv.setPropertyString("start", if (start > 0) start.toString() else "none")
        if (surfaceAttached) {
            mpv.command(arrayOf("loadfile", url, "replace"))
        } else {
            pendingFile = url
        }
    }

    fun togglePause() = mpv.command(arrayOf("cycle", "pause"))

    fun pause() = mpv.setPropertyBoolean("pause", true)

    /** To the keyframe nearest [seconds]: no decoding from the keyframe up to the exact time, which costs seconds in software decoding. */
    fun seekTo(seconds: Double) {
        markSeek()
        mpv.command(arrayOf("seek", seconds.toString(), "absolute+keyframes"))
    }

    fun seekBy(seconds: Int) {
        markSeek()
        mpv.command(arrayOf("seek", seconds.toString(), "relative"))
    }

    /** A seek made while another one is under way is timed from the first. */
    private fun markSeek() {
        if (openMs != null && seekAt == 0L) seekAt = SystemClock.elapsedRealtime()
    }

    /** What the viewer waited for since the file was asked for: opening, seeks, stalls. */
    fun figures(): PlayerFigures {
        fun p(name: String) = mpv.getPropertyString(name)?.takeIf { it.isNotBlank() }
        val video = listOfNotNull(
            p("video-format"),
            p("video-params/w")?.let { w -> p("video-params/h")?.let { h -> "${w}x$h" } },
            p("video-params/pixelformat"),
            p("hwdec-current")?.takeIf { it != "no" } ?: "logiciel",
        ).joinToString(" ")
        val now = SystemClock.elapsedRealtime()
        return PlayerFigures(
            openMs = openMs,
            loadedMs = loadedMs,
            seeksMs = seeks.toList(),
            stalls = stalls,
            stalledMs = stalledMs + (if (stallAt != 0L) now - stallAt else 0L),
            watchedS = if (firstFrameAt != 0L) (now - firstFrameAt) / 1000 else 0L,
            video = video.ifBlank { null },
            droppedFrames = p("frame-drop-count")?.toIntOrNull(),
            duration = _duration.value,
        )
    }

    fun selectAudio(id: Int) = mpv.setPropertyString("aid", id.toString())

    /** [NO_SUBTITLES] turns them off. */
    fun selectSubtitles(id: Int) = mpv.setPropertyString("sid", if (id == NO_SUBTITLES) "no" else id.toString())

    /** Subtitles later (positive) or earlier, in seconds; kept for the next files. */
    fun shiftSubtitles(seconds: Double) = mpv.command(arrayOf("add", "sub-delay", seconds.toString()))

    fun setSpeed(speed: Double) = mpv.setPropertyDouble("speed", speed)

    fun toggleFill() {
        _fill.value = !_fill.value
        mpv.setPropertyDouble("panscan", if (_fill.value) 1.0 else 0.0)
    }

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
        surfaceAttached = true
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
        surfaceAttached = false
    }

    // --- MPVLib.EventObserver (mpv event thread) ---

    override fun eventProperty(property: String) {
        if (property == "track-list") publishTracks()
    }

    override fun eventProperty(property: String, value: Long) {}

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> _position.value = value
            "duration" -> _duration.value = value
            "sub-delay" -> _subtitleDelay.value = value
            "speed" -> _speed.value = value
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> _paused.value = value
            "paused-for-cache" -> {
                _buffering.value = value
                countStall(value)
            }
            "core-idle" -> if (!value) _buffering.value = false
            "eof-reached" -> _ended.value = value
        }
    }

    override fun eventProperty(property: String, value: String) {}

    /** A wait for the network while playing; those of the opening and of a seek are timed with them. */
    private fun countStall(waiting: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (waiting) {
            if (openMs != null && seekAt == 0L && stallAt == 0L) stallAt = now
        } else if (stallAt != 0L) {
            // A blink of the cache indicator isn't a stall the viewer sees.
            if (now - stallAt >= MIN_STALL_MS) {
                stalls++
                stalledMs += now - stallAt
            }
            stallAt = 0L
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            // Playback starts, or starts again after a seek: the picture moves.
            PLAYBACK_RESTART -> {
                val now = SystemClock.elapsedRealtime()
                if (openMs == null) {
                    openMs = now - loadAt
                    firstFrameAt = now
                } else if (seekAt != 0L) {
                    seeks += now - seekAt
                    seekAt = 0L
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                if (loadedMs == null) loadedMs = SystemClock.elapsedRealtime() - loadAt
                // mpv only finds subtitles next to local files: add the NAS ones by hand.
                externalSubtitles.forEach { mpv.command(arrayOf("sub-add", it, "auto")) }
                selectTracks()
                publishTracks()
            }
            // After the end of the file replaced, if any: this file's own events follow.
            START_FILE -> started = true
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                if (started && openMs == null) openFailed = true
                Log.i(TAG, "end of file")
            }
        }
    }

    /** Applies the language settings; any failure leaves mpv's own choice, playback goes on. */
    private fun selectTracks() {
        try {
            val tracks = readTracks()
            fun of(type: String) = tracks.filter { it.type == type }
                .map { Track(it.id, it.lang, it.title, it.forced, it.default) }
            val choice = chooseTracks(audio = of("audio"), subtitles = of("sub"), settings = settings)
            choice.audio?.let(::selectAudio)
            choice.subtitles?.let(::selectSubtitles)
            Log.i(TAG, "tracks: audio ${choice.audio}, subtitles ${choice.subtitles}")
        } catch (e: Exception) {
            Log.w(TAG, "track selection failed", e)
        }
    }

    private fun publishTracks() {
        val tracks = try {
            readTracks()
        } catch (e: Exception) {
            Log.w(TAG, "track list unreadable", e)
            return
        }
        _audioTracks.value = tracks.filter { it.type == "audio" }.map { it.toPlayerTrack() }
        _subtitleTracks.value = tracks.filter { it.type == "sub" }.map { it.toPlayerTrack() }
    }

    private fun readTracks(): List<MpvTrack> =
        (0 until (mpv.getPropertyString("track-list/count")?.toIntOrNull() ?: 0)).mapNotNull { i ->
            fun p(name: String) = mpv.getPropertyString("track-list/$i/$name")
            MpvTrack(
                type = p("type") ?: return@mapNotNull null,
                id = p("id")?.toIntOrNull() ?: return@mapNotNull null,
                lang = p("lang"),
                title = p("title"),
                codec = p("codec"),
                channels = p("demux-channel-count")?.toIntOrNull(),
                forced = p("forced") == "yes",
                default = p("default") == "yes",
                external = p("external") == "yes",
                selected = p("selected") == "yes",
            )
        }

    private class MpvTrack(
        val type: String,
        val id: Int,
        val lang: String?,
        val title: String?,
        val codec: String?,
        val channels: Int?,
        val forced: Boolean,
        val default: Boolean,
        val external: Boolean,
        val selected: Boolean,
    ) {
        /** "Anglais" / "Commentaires · E-AC3 · 5.1 · par défaut"; what the title already says is not repeated. */
        fun toPlayerTrack(): PlayerTrack {
            val track = Track(id, lang, title, forced, default)
            val language = Language.entries.firstOrNull(track::isIn)?.label ?: lang?.let(::languageName)
            val label = language ?: title?.takeIf { it.isNotBlank() } ?: "Piste $id"
            val shownTitle = title?.takeIf { language != null && it.isNotBlank() }
            fun new(text: String?) = text?.takeIf { shownTitle?.contains(it, ignoreCase = true) != true }
            val detail = listOfNotNull(
                shownTitle,
                new(codec?.let(::codecName)),
                new(channels?.let(::channelLayout)),
                // Forced audio means nothing to the viewer; forced subtitles only cover foreign dialogue.
                new("forcés").takeIf { type == "sub" && track.isForced && title?.contains("forc", ignoreCase = true) != true },
                "externe".takeIf { external },
                "par défaut".takeIf { default },
            )
            return PlayerTrack(id, label, detail.joinToString(" · "), selected)
        }
    }

    private companion object {
        const val TAG = "MpvPlayer"
        const val VO = "gpu-next"

        /** MPV_EVENT_PLAYBACK_RESTART in mpv's client.h. */
        const val PLAYBACK_RESTART = 21

        /** MPV_EVENT_START_FILE. */
        const val START_FILE = 6

        const val MIN_STALL_MS = 300L

        fun languageName(tag: String): String? {
            val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.FRENCH)
            return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
                ?.replaceFirstChar { it.titlecase(Locale.FRENCH) }
        }

        fun codecName(codec: String) = when (codec.lowercase()) {
            "hdmv_pgs_subtitle" -> "PGS"
            "subrip" -> "SRT"
            "dvd_subtitle" -> "VobSub"
            "mov_text" -> "TX3G"
            "webvtt" -> "WebVTT"
            "eac3" -> "E-AC3"
            "truehd" -> "TrueHD"
            "opus" -> "Opus"
            "vorbis" -> "Vorbis"
            else -> codec.uppercase()
        }

        fun channelLayout(channels: Int) = when (channels) {
            1 -> "mono"
            2 -> "stéréo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels canaux"
        }
    }
}
