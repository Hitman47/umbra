package io.github.mkdevtests.umbra.player

import android.content.Context
import kotlin.math.abs
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
import io.github.mkdevtests.umbra.settings.TrackChoice
import io.github.mkdevtests.umbra.settings.TrackMemory
import io.github.mkdevtests.umbra.settings.rememberedTracks
import io.github.mkdevtests.umbra.subtitles.OnlineTrack
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
    private val appContext = context.applicationContext

    private val mpv = MPVLib.create(context.applicationContext)
        ?: error("libmpv could not be created")
    private var pendingFile: String? = null
    private var surfaceAttached = false
    private var externalSubtitles: List<String> = emptyList()
    private var onlineSubtitles: List<OnlineTrack> = emptyList()

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

    private val _audioDelay = MutableStateFlow(0.0)
    val audioDelay: StateFlow<Double> = _audioDelay.asStateFlow()

    /** The file's chapters: intro and credits are offered to skip. */
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _nightAudio = MutableStateFlow(false)
    /** Dialogue louder, explosions softer (a compressor on the sound). */
    val nightAudio: StateFlow<Boolean> = _nightAudio.asStateFlow()

    private val _animeUpscale = MutableStateFlow(false)
    /** Anime4K shaders on the picture. */
    val animeUpscale: StateFlow<Boolean> = _animeUpscale.asStateFlow()

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

    @Volatile private var seekAt = 0L
    private val seeks = java.util.Collections.synchronizedList(mutableListOf<Long>())
    @Volatile private var stallAt = 0L
    @Volatile private var stalls = 0
    @Volatile private var stalledMs = 0L

    /** mpv's log while a file opens, read when it fails; stopped once the picture plays. */
    private val logFile = java.io.File(context.cacheDir, "player.log")
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The file of this load has started: an end of file before is the previous one's. */
    @Volatile private var started = false

    private val _failure = MutableStateFlow<String?>(null)

    /** Why the file doesn't play (no picture): the player's errors, the codecs; null while it plays or opens. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val watchdog = Runnable { if (openMs == null && !_ended.value) fail("Aucune image au bout de ${FAILURE_AFTER_MS / 1000} s.") }

    /** The file played to its end (mpv keeps the last frame: keep-open). */
    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended.asStateFlow()

    private val tv = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    /** A TV's GPU is weaker than a tablet's: mpv's lighter renderer there, for SDR pictures. */
    private val vo = if (tv) TV_VO else VO

    /**
     * How the picture is drawn now. On a TV it follows the file: the light renderer for
     * SDR; for HDR or Dolby Vision, the TV's own decoder straight to the screen ([DIRECT]:
     * native HDR, nearly no work; mpv draws no subtitles then, the app shows their text),
     * or the full renderer when the subtitles are pictures (PGS, VobSub) mpv must draw.
     */
    @Volatile private var render = vo

    private val _direct = MutableStateFlow(false)

    /** The picture goes straight to the screen: the subtitles' text is shown by the app ([subtitleText]). */
    val direct: StateFlow<Boolean> = _direct.asStateFlow()

    private val _passthrough = MutableStateFlow(settings.audioPassthrough)

    /** Dolby / DTS sent as they are to an amplifier that takes them (Réglages, or the player's panel). */
    val passthrough: StateFlow<Boolean> = _passthrough.asStateFlow()

    private val _subtitleText = MutableStateFlow("")
    val subtitleText: StateFlow<String> = _subtitleText.asStateFlow()

    init {
        val cacheDir = context.cacheDir.absolutePath
        mpv.setOptionString("config", "no")
        mpv.setOptionString("gpu-shader-cache-dir", cacheDir)
        mpv.setOptionString("icc-cache-dir", cacheDir)

        // Render through the GPU so mpv applies its shaders: HDR / Dolby Vision
        // tone mapping and ASS subtitles. "mediacodec" hands decoded frames to
        // the GPU without a copy (AImageReader); "mediacodec-copy" is the
        // fallback. At 4K the copy alone made playback stutter.
        mpv.setOptionString("vo", vo)
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        // Cheap scalers, no dithering, no per-frame HDR peak detection:
        // the default quality settings drop frames on 4K HDR with a tablet GPU.
        mpv.setOptionString("profile", "fast")
        // VC-1 / WMV and MPEG-1 too: a TV's processor can't decode them alone.
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,mpeg1video,vc1,wmv3,vp8,vp9,av1")

        // TrueHD / DTS are decoded in software and downmixed for the device.
        mpv.setOptionString("ao", "audiotrack,opensles")
        // An amplifier or a sound bar (HDMI, eARC) that takes Dolby / DTS: sent as it is (see play()).

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
        // Seeks, and the resume point, land on the nearest keyframe: a precise seek decodes
        // everything from the keyframe before, up to 4-5 s measured on a resumed film.
        mpv.setOptionString("hr-seek", "no")
        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("demuxer-max-bytes", "64MiB")
        mpv.setOptionString("demuxer-max-back-bytes", "32MiB")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("input-default-bindings", "no")
        // No youtube-dl on Android: its hook only adds a misleading error.
        mpv.setOptionString("ytdl", "no")

        // While a file opens, mpv's messages go to a file: they tell why a picture never comes.
        mpv.setOptionString("log-file", logFile.absolutePath)
        mpv.init()

        mpv.setOptionString("save-position-on-quit", "no")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("idle", "once")

        if (settings.nightAudio) setNightAudio(true)
        if (settings.animeUpscale) setAnimeUpscale(true)

        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("core-idle", MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("track-list", MpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("sub-delay", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("audio-delay", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("chapter-list", MpvFormat.MPV_FORMAT_NONE)
        mpv.observeProperty("speed", MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("sub-text", MpvFormat.MPV_FORMAT_STRING)
    }

    /** Plays [url] from [start] seconds with extra subtitle files: now, or as soon as a Surface is available. */
    fun play(url: String, subtitles: List<String> = emptyList(), start: Double = 0.0, online: List<OnlineTrack> = emptyList(), remote: Boolean = false) {
        // Through Tailscale: a deeper cache, and a few seconds stored before the picture starts, against the network's ups and downs.
        // A TV has little memory for the app: a smaller cache there (the NAS answers fast at home).
        mpv.setPropertyString("demuxer-max-bytes", if (remote) (if (tv) "128MiB" else "256MiB") else if (tv) "32MiB" else "64MiB")
        mpv.setPropertyString("demuxer-max-back-bytes", if (remote) (if (tv) "32MiB" else "64MiB") else if (tv) "16MiB" else "32MiB")
        mpv.setPropertyString("cache-pause-initial", if (remote) "yes" else "no")
        mpv.setPropertyString("cache-pause-wait", if (remote) "3" else "1")
        externalSubtitles = subtitles
        onlineSubtitles = online
        changedByHand = false
        _ended.value = false
        _buffering.value = true
        // The previous file's values must not be saved as this one's progress.
        _position.value = 0.0
        _duration.value = 0.0
        _chapters.value = emptyList()
        loadAt = SystemClock.elapsedRealtime()
        firstFrameAt = 0L
        openMs = null
        loadedMs = null
        seekAt = 0L
        seeks.clear()
        stallAt = 0L
        stalls = 0
        stalledMs = 0L
        mpv.setPropertyString("start", if (start > 0) start.toString() else "none")
        mpv.setPropertyString("audio-spdif", passthroughCodecs())
        // Each file starts with the light renderer; HDR or Dolby Vision switch it once the picture is known.
        if (tv && render != vo) switchRender(vo)
        _failure.value = null
        started = false
        runCatching {
            logFile.writeText("")
            mpv.setPropertyString("log-file", logFile.absolutePath)
        }
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, FAILURE_AFTER_MS)
        if (surfaceAttached) {
            mpv.command(arrayOf("loadfile", url, "replace"))
        } else {
            pendingFile = url
        }
    }

    /**
     * The commands from the screen run on their own thread, one after the
     * other: mpv answers a command once its core is free, and while it waits
     * on the network (a seek through the NAS) the screen would freeze, until
     * Android closes the player.
     */
    private val commands = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "mpv-commands") }
    @Volatile private var released = false

    private fun async(block: () -> Unit) {
        if (released) return
        runCatching { commands.execute { if (!released) runCatching(block).onFailure { Log.w(TAG, "mpv command", it) } } }
    }

    fun togglePause() = async { mpv.command(arrayOf("cycle", "pause")) }

    fun pause() = async { mpv.setPropertyBoolean("pause", true) }

    // Seeks: one at a time, the next once the picture moves again; meanwhile only the last asked waits.
    private val seekLock = Any()
    private var seeking = false
    private var pendingSeek: Double? = null

    /** Where the seeks asked for lead, until the picture is there; null when none is under way. */
    @Volatile private var seekTarget: Double? = null
    private val seekTimeout = Runnable { seekDone() }

    /** To the keyframe nearest [seconds]: no decoding from the keyframe up to the exact time, which costs seconds in software decoding. */
    fun seekTo(seconds: Double) = requestSeek(seconds.coerceAtLeast(0.0))

    /** [seconds] from where the seeks under way lead (presses in a row add up), else from the position. */
    fun seekBy(seconds: Int) = requestSeek(((seekTarget ?: _position.value) + seconds).coerceIn(0.0, _duration.value.takeIf { it > 0 } ?: Double.MAX_VALUE))

    private fun requestSeek(target: Double) {
        synchronized(seekLock) {
            seekTarget = target
            pendingSeek = target
            if (!seeking) startSeek()
        }
    }

    private fun startSeek() {
        val target = pendingSeek ?: return
        pendingSeek = null
        seeking = true
        markSeek()
        async { mpv.command(arrayOf("seek", target.toString(), "absolute+keyframes")) }
        // A seek mpv never finishes (file ended, error) must not block the next ones.
        main.removeCallbacks(seekTimeout)
        main.postDelayed(seekTimeout, SEEK_TIMEOUT_MS)
    }

    /** The picture moves again: the next seek asked meanwhile, if any. */
    private fun seekDone() {
        synchronized(seekLock) {
            main.removeCallbacks(seekTimeout)
            seeking = false
            if (pendingSeek != null) startSeek() else seekTarget = null
        }
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
    fun selectSubtitles(id: Int) {
        mpv.setPropertyString("sid", if (id == NO_SUBTITLES) "no" else id.toString())
        // Picture subtitles need mpv's renderer; text ones can be shown over the TV's own picture.
        if (tv) async { adaptRender() }
    }

    /** Subtitles later (positive) or earlier, in seconds; kept for the next files. */
    fun shiftSubtitles(seconds: Double) = mpv.command(arrayOf("add", "sub-delay", seconds.toString()))

    /** Sound later (positive) or earlier, in seconds. */
    fun shiftAudio(seconds: Double) = mpv.command(arrayOf("add", "audio-delay", seconds.toString()))

    /** Adds a downloaded subtitle file and shows it, in place of the one downloaded before in its language. */
    fun addSubtitles(track: OnlineTrack) {
        readTracks().filter { it.type == "sub" && it.external && it.title == track.title }.forEach {
            mpv.command(arrayOf("sub-remove", it.id.toString()))
        }
        mpv.command(arrayOf("sub-add", track.path, "select", track.title, track.language))
    }

    /** No video decoding while only the sound plays (background): the picture comes back with [enabled]. */
    fun setVideoEnabled(enabled: Boolean) = mpv.setPropertyString("vid", if (enabled) "auto" else "no")

    /** The picture's size as displayed (aspect applied), for the picture-in-picture window. */
    fun videoSize(): Pair<Int, Int>? {
        val width = mpv.getPropertyString("video-params/dw")?.toIntOrNull() ?: return null
        val height = mpv.getPropertyString("video-params/dh")?.toIntOrNull() ?: return null
        return (width to height).takeIf { width > 0 && height > 0 }
    }

    fun setNightAudio(on: Boolean) {
        mpv.command(arrayOf("af", "remove", "@night"))
        if (on) mpv.command(arrayOf("af", "add", "@night:lavfi=[$NIGHT_FILTER]"))
        _nightAudio.value = on
        // The night mode needs the decoded sound: no Dolby / DTS sent as it is meanwhile.
        mpv.setPropertyString("audio-spdif", passthroughCodecs())
    }

    /** Anime4K (MIT, bundled in the assets): restores and doubles the lines of a drawn picture, on the GPU. */
    fun setAnimeUpscale(on: Boolean) {
        val shaders = if (on) anime4k().joinToString(":") else ""
        runCatching { mpv.setPropertyString("glsl-shaders", shaders) }.onFailure { Log.w(TAG, "shaders", it) }
        _animeUpscale.value = on
    }

    /** The shader files, copied once from the assets to where mpv can read them. */
    private fun anime4k(): List<String> {
        val folder = appContext.filesDir.resolve("anime4k").apply { mkdirs() }
        return ANIME4K.map { name ->
            val file = folder.resolve(name)
            if (!file.exists()) appContext.assets.open("anime4k/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            file.absolutePath
        }
    }

    val isPlaying get() = !_paused.value && !_ended.value && _duration.value > 0

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
            val hw = p("hwdec-current")
            appendLine("hwdec: ${if (hw == "no") "logiciel (peut saccader)" else hw}  vo: ${p("current-vo")}")
            appendLine("son direct : ${p("audio-spdif").ifBlank { "non (décodé)" }}")
            appendLine("gamma: ${p("video-params/gamma")}  primaries: ${p("video-params/primaries")}")
            appendLine("audio: ${p("audio-codec-name")}  ${p("audio-params/channel-count")} ch")
            append("fps: ${p("estimated-vf-fps")}  dropped: ${p("frame-drop-count")}  cache: ${p("demuxer-cache-duration")} s")
        }
    }

    /** Why the file doesn't play: [reason], what the file holds, and the player's warnings and errors while opening. */
    private fun fail(reason: String) {
        if (_failure.value != null) return
        fun p(name: String) = runCatching { mpv.getPropertyString(name) }.getOrNull()?.takeIf { it.isNotBlank() }
        val file = listOfNotNull(
            p("file-format")?.let { "Conteneur : $it" },
            p("video-codec")?.let { "Vidéo : $it" + (p("hwdec-current")?.let { hw -> " (décodeur : $hw)" } ?: "") },
            p("audio-codec-name")?.let { "Audio : $it" },
            p("track-list/count")?.let { "Pistes : $it" },
        )
        val messages = runCatching { logFile.readLines() }.getOrDefault(emptyList())
            .filter { LOG_PROBLEM.containsMatchIn(it) }
            .takeLast(30)
        // The local server's answer "HTTP 500" says nothing: the NAS's own refusal, if it happened for this file.
        val nas = io.github.mkdevtests.umbra.nas.LocalStreamServer.lastOpenError
            ?.takeIf { it.at >= System.currentTimeMillis() - (SystemClock.elapsedRealtime() - loadAt) - 1000 }
            ?.let { listOf("Ouverture sur le NAS : ${it.key}", it.message) }
            .orEmpty()
        _failure.value = (listOf(reason) + nas + file + listOf("") + messages.ifEmpty { listOf("(aucun message d'erreur du lecteur)") }).joinToString("\n")
    }

    fun release() {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(seekTimeout)
        mpv.removeObserver(this)
        // After the commands still queued, off the screen's thread; nothing runs on mpv after it.
        commands.execute {
            released = true
            mpv.destroy()
        }
        commands.shutdown()
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
            mpv.setPropertyString("vo", if (render == DIRECT) DIRECT_VO else render)
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
        when (property) {
            "track-list" -> publishTracks()
            "chapter-list" -> publishChapters()
        }
    }

    override fun eventProperty(property: String, value: Long) {}

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            // Every frame otherwise (24 to 60 a second): the screens showing it would redraw as often.
            "time-pos" -> if (abs(value - _position.value) >= POSITION_STEP) _position.value = value
            "duration" -> _duration.value = value
            "sub-delay" -> _subtitleDelay.value = value
            "audio-delay" -> _audioDelay.value = value
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

    override fun eventProperty(property: String, value: String) {
        if (property == "sub-text") _subtitleText.value = value
    }

    /** The codecs the HDMI output takes as they are, for mpv's "audio-spdif" ("" : all decoded). */
    private fun passthroughCodecs(): String {
        // The night mode works on the decoded sound.
        if (!_passthrough.value || _nightAudio.value) return ""
        return outputCodecs()
    }

    /** What the HDMI output takes as it is ("" : none, the sound is decoded). */
    fun outputCodecs(): String {
        val audio = appContext.getSystemService(android.media.AudioManager::class.java) ?: return ""
        val hdmi = setOf(android.media.AudioDeviceInfo.TYPE_HDMI, android.media.AudioDeviceInfo.TYPE_HDMI_ARC, android.media.AudioDeviceInfo.TYPE_HDMI_EARC)
        val encodings = audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in hdmi }
            .flatMap { it.encodings.toList() }
            .toSet()
        return passthroughFor(encodings)
    }

    fun setPassthrough(on: Boolean) {
        _passthrough.value = on
        mpv.setPropertyString("audio-spdif", passthroughCodecs())
    }

    /** The picture is HDR or Dolby Vision (known once decoded). */
    private fun isHdr(): Boolean {
        val gamma = mpv.getPropertyString("video-params/gamma").orEmpty()
        if (gamma == "pq" || gamma == "hlg") return true
        val count = mpv.getPropertyString("track-list/count")?.toIntOrNull() ?: 0
        return (0 until count).any { i ->
            mpv.getPropertyString("track-list/$i/type") == "video" && mpv.getPropertyString("track-list/$i/selected") == "yes" &&
                !mpv.getPropertyString("track-list/$i/dolby-vision-profile").isNullOrBlank()
        }
    }

    /** The renderer this file needs on a TV (see [render]). */
    private fun adaptRender() {
        if (!tv) return
        runCatching {
            val target = when {
                !isHdr() -> vo
                settings.fullRender -> VO
                readTracks().any { it.type == "sub" && it.selected && it.codec?.lowercase() in IMAGE_SUBTITLES } -> VO
                else -> DIRECT
            }
            switchRender(target)
        }.onFailure { Log.w(TAG, "render", it) }
    }

    private fun switchRender(target: String) {
        if (target == render) return
        Log.i(TAG, "render $render -> $target")
        render = target
        mpv.setPropertyString("vo", if (target == DIRECT) DIRECT_VO else target)
        _direct.value = target == DIRECT
        if (target != DIRECT) _subtitleText.value = ""
    }

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
                seekDone()
                val now = SystemClock.elapsedRealtime()
                if (openMs == null) {
                    openMs = now - loadAt
                    firstFrameAt = now
                    main.removeCallbacks(watchdog)
                    // The picture plays: no more log (it would grow for the whole film).
                    main.postDelayed({ runCatching { mpv.setPropertyString("log-file", "") } }, 10_000)
                } else if (seekAt != 0L) {
                    seeks += now - seekAt
                    seekAt = 0L
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                if (loadedMs == null) loadedMs = SystemClock.elapsedRealtime() - loadAt
                // mpv only finds subtitles next to local files: add the NAS ones by hand.
                externalSubtitles.forEach { mpv.command(arrayOf("sub-add", it, "auto")) }
                // Subtitles downloaded for this video before: back, named, so the profile can pick them.
                onlineSubtitles.forEach { mpv.command(arrayOf("sub-add", it.path, "auto", it.title, it.language)) }
                selectTracks()
                publishTracks()
                publishChapters()
            }
            START_FILE -> started = true
            // The picture's format is known: on a TV, the renderer it needs.
            VIDEO_RECONFIG -> if (tv) async { adaptRender() }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                Log.i(TAG, "end of file")
                // Ended before its first picture: it couldn't be played.
                if (started && openMs == null) main.post { fail("Le lecteur s'est arrêté avant la première image.") }
            }
        }
    }

    /** The tracks chosen by hand for this file's series: they win over the language settings (see [rememberedTracks]). */
    @Volatile var memory: TrackMemory? = null

    /** Audio, subtitles or their delay changed in the panel since this file started. */
    @Volatile var changedByHand = false

    /** The tracks now playing, to keep for the series' next episodes. */
    fun trackMemory(): TrackMemory? = runCatching {
        val tracks = readTracks()
        val audio = tracks.firstOrNull { it.type == "audio" && it.selected }
        val sub = tracks.firstOrNull { it.type == "sub" && it.selected }
        TrackMemory(
            audioLang = audio?.lang, audioTitle = audio?.title,
            subtitlesOff = sub == null, subLang = sub?.lang, subTitle = sub?.title,
            subForced = sub?.let { Track(it.id, it.lang, it.title, it.forced).isForced } ?: false,
            subDelay = _subtitleDelay.value,
        )
    }.getOrNull()

    /** Applies the language settings; any failure leaves mpv's own choice, playback goes on. */
    private fun selectTracks() {
        try {
            val tracks = readTracks()
            fun of(type: String) = tracks.filter { it.type == type }
                .map { Track(it.id, it.lang, it.title, it.forced, it.default) }
            val usual = chooseTracks(audio = of("audio"), subtitles = of("sub"), settings = settings)
            val remembered = memory?.let { rememberedTracks(it, of("audio"), of("sub")) }
            val choice = TrackChoice(remembered?.audio ?: usual.audio, remembered?.subtitles ?: usual.subtitles)
            memory?.let { mpv.setPropertyString("sub-delay", it.subDelay.toString()) }
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

    private fun publishChapters() {
        _chapters.value = try {
            (0 until (mpv.getPropertyString("chapter-list/count")?.toIntOrNull() ?: 0)).mapNotNull { i ->
                val start = mpv.getPropertyString("chapter-list/$i/time")?.toDoubleOrNull() ?: return@mapNotNull null
                Chapter(mpv.getPropertyString("chapter-list/$i/title").orEmpty(), start)
            }
        } catch (e: Exception) {
            Log.w(TAG, "chapters unreadable", e)
            emptyList()
        }
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
            return PlayerTrack(id, label, detail.joinToString(" · "), selected, lang)
        }
    }

    private companion object {
        const val TAG = "MpvPlayer"
        const val VO = "gpu-next"
        const val TV_VO = "gpu"

        /** On a TV: the picture straight from its decoder to the screen. */
        const val DIRECT = "direct"
        const val DIRECT_VO = "mediacodec_embed"

        /** Subtitles drawn as pictures: only mpv's renderer shows them. */
        val IMAGE_SUBTITLES = setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "pgssub", "dvdsub", "dvbsub", "xsub")

        /** MPV_EVENT_VIDEO_RECONFIG in mpv's client.h. */
        const val VIDEO_RECONFIG = 17

        /** The playback position is published to the screens when it moved this much (seconds). */
        const val POSITION_STEP = 0.25

        /** No picture after this long: the file is reported as not playing. */
        const val FAILURE_AFTER_MS = 25_000L

        /** mpv's log lines worth showing: fatal, errors, warnings ("[  1.234][e][ffmpeg] …"). */
        val LOG_PROBLEM = Regex("""\]\[[few]\]""")

        /** MPV_EVENT_START_FILE in mpv's client.h. */
        const val START_FILE = 6

        /** MPV_EVENT_PLAYBACK_RESTART in mpv's client.h. */
        const val PLAYBACK_RESTART = 21

        /** A seek not finished after this: the next one goes anyway. */
        const val SEEK_TIMEOUT_MS = 4_000L

        const val MIN_STALL_MS = 300L

        /** A gentle compressor, then the level brought back up: quiet lines heard, loud scenes tamed. */
        const val NIGHT_FILTER = "acompressor=threshold=0.05:ratio=4:attack=20:release=250:makeup=3"

        /** Anime4K's light "S" chain: clamp highlights, restore lines, double the size. */
        val ANIME4K = listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_S.glsl", "Anime4K_Upscale_CNN_x2_S.glsl")

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
