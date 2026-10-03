package io.github.mkdevtests.umbra.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.nas.isTailnet
import io.github.mkdevtests.umbra.settings.NO_SUBTITLES
import io.github.mkdevtests.umbra.settings.Language
import io.github.mkdevtests.umbra.subtitles.OnlineSubtitle
import io.github.mkdevtests.umbra.subtitles.OnlineTrack
import io.github.mkdevtests.umbra.subtitles.OpenSubtitlesStatus
import io.github.mkdevtests.umbra.subtitles.subtitleLanguageName
import io.github.mkdevtests.umbra.subtitles.SubtitleQuery
import io.github.mkdevtests.umbra.subtitles.movieHash
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.settings.Settings
import io.github.mkdevtests.umbra.trakt.TraktEndpoint
import io.github.mkdevtests.umbra.ui.theme.NyxaraTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import io.github.mkdevtests.umbra.nas.toUserMessage
import io.github.mkdevtests.umbra.nas.within
import io.github.mkdevtests.umbra.perso.PersoFolderState
import io.github.mkdevtests.umbra.perso.PersoVideo
import io.github.mkdevtests.umbra.perso.firstOf
import io.github.mkdevtests.umbra.perso.videosUnder
import io.github.mkdevtests.umbra.ui.perso.persoDownloadKey
import kotlin.math.abs
import kotlin.math.sign

/**
 * Full-screen player. Plays the queue in [EXTRA_QUEUE]: a film, or an episode
 * and the ones after it; or a Perso folder ([EXTRA_PERSO]), its queue built
 * here from its whole tree, endless, with its own history.
 */
class PlayerActivity : ComponentActivity() {

    private lateinit var player: MpvPlayer

    /** What plays, what comes next; null while a Perso folder is walked. */
    private var queue by mutableStateOf<PlayerQueue?>(null)

    /** The item playing. */
    private val current get() = queue?.current

    /** The Perso folder played, null for the library's videos. */
    private var perso: PersoRequest? = null
    private var persoVideos: Map<String, PersoVideo> = emptyMap()

    /** While a Perso folder is walked: what is happening, or why it can't play. */
    private var preparing by mutableStateOf<String?>(null)

    /** Bumped at each file started: one measure, one prefetch per file. */
    private var started = 0
    private val app get() = application as NyxaraApp
    private val settings get() = app.settings.settings.value

    /** In the picture-in-picture window: only the picture is drawn. */
    private var inPip by mutableStateOf(false)

    /** Playing out of sight, the sound only (Réglages › Son en arrière-plan). */
    private var background = false

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_TOGGLE) player.togglePause()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        perso = intent.getStringExtra(EXTRA_PERSO)?.let { Json.decodeFromString(PersoRequest.serializer(), it) }
        val items = intent.getStringExtra(EXTRA_QUEUE)?.let { Json.decodeFromString(QUEUE, it) }.orEmpty()
        if (items.isEmpty() && perso == null) return finish()
        player = MpvPlayer(this, settings)
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        perso?.let(::preparePerso) ?: run {
            // A film, or an episode and the next ones: in order, no loop.
            val byKey = items.associateBy(PlayItem::key)
            val built = PlayerQueue(PlayOrder(byKey.keys.toList(), shuffle = false, repeat = Repeat.None), { byKey.getValue(it) }, perso = false)
            queue = built
            start(built.begin(items[0].key))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(10_000)
                    saveProgress()
                }
            }
        }
        // The end of a file is saved at once: it marks the episode watched.
        lifecycleScope.launch {
            player.ended.collect {
                if (it) {
                    saveProgress()
                    scrobbler.stop()
                    val queue = queue
                    when {
                        queue == null -> Unit
                        // Arrêter après : it stays there.
                        queue.stopAfter -> Unit
                        queue.repeat == Repeat.One -> current?.let { start(it.copy(start = 0.0)) }
                        // Out of sight, nobody sees the countdown: the next episode starts at once. Perso: always.
                        (background || inPip || queue.perso) && queue.upNext != null -> playNext()
                    }
                }
            }
        }
        // The last minute and a half, or the credits: the next episode's start is read ahead, it opens at once.
        lifecycleScope.launch {
            var warmed = -1
            combine(player.position, player.duration) { position, duration -> duration > 0 && duration - position < PREFETCH_BEFORE_END }
                .distinctUntilChanged()
                .collect { near ->
                    val next = queue?.upNext
                    if (near && next != null && warmed != started) {
                        warmed = started
                        (next.stream ?: next.file)?.let { app.streamServer.prefetch(it) }
                    }
                }
        }
        // The picture-in-picture window follows the picture's shape and the play/pause state.
        lifecycleScope.launch {
            combine(player.paused, player.duration, player.ended) { paused, duration, ended -> !paused && duration > 0 && !ended }
                .distinctUntilChanged()
                .collect { playing ->
                    updatePip()
                    if (background) {
                        BackgroundPlayback.playing = playing
                        PlaybackService.update(this@PlayerActivity)
                    }
                }
        }
        // Trakt follows what is played: start when the picture moves, pause with it.
        lifecycleScope.launch {
            combine(player.paused, player.duration, player.ended) { paused, duration, ended -> duration > 0 && !paused && !ended }
                .distinctUntilChanged()
                .collect { playing -> if (playing) scrobbler.start() else scrobbler.pause() }
        }

        setContent {
            NyxaraTheme {
                val queue = queue
                val item = queue?.current
                if (queue == null || item == null) {
                    Preparing(preparing ?: "Préparation…", onBack = ::finish)
                } else {
                    PlayerScreen(
                        player = player,
                        item = item,
                        queue = queue,
                        settings = settings,
                        inPip = inPip,
                        onlineHook = if (perso == null) onlineSubtitles() else null,
                        onNext = ::playNext,
                        onJump = ::jumpTo,
                        onPip = ::enterPip,
                        onBack = ::finish,
                        onPrevious = ::playPrevious,
                    )
                }
            }
        }
    }

    /** Walks the Perso folder, then plays: the video asked, or where it stopped, or the next in its order. */
    /**
     * Plays a Perso folder without waiting for the whole of it: at once from
     * its last walk (kept on the device), or the video asked, or the one that
     * stopped midway; else, shuffled, as soon as the first videos are found.
     * The walk goes on meanwhile and the queue grows with it.
     */
    private fun preparePerso(request: PersoRequest) {
        lifecycleScope.launch {
            val nas = app.nas
            val offline = request.start?.let { app.downloads.localFile(persoDownloadKey(it)) } != null
            if (request.only || nas == null) {
                // Downloaded: played alone, NAS or not.
                if (offline && request.start != null) beginPerso(request, listOf(request.start), complete = true, shuffle = false)
                else preparing = if (request.only) "Vidéo introuvable sur l'appareil." else "NAS injoignable."
                return@launch
            }
            // A catalogue profile: its videos are known, nothing to walk.
            if (request.profile != null) {
                val index = app.catalog.index.value
                val keys = request.selection ?: index?.let { catalog ->
                    val videos = if (request.profile.isEmpty()) catalog.ungrouped else catalog.videosOf(request.profile)
                    videos.mapNotNull(catalog::nasPath)
                }.orEmpty()
                if (keys.isEmpty()) preparing = "Aucune vidéo." else beginPerso(request, keys, complete = true)
                return@launch
            }
            val state = app.perso.folder(request.folder)
            val resume = state.last?.takeIf { app.perso.progress.value[it]?.inProgress == true && it.within(request.folder) }
            val cached = withContext(Dispatchers.IO) { app.persoTrees.load(request.folder) }
            val known = LinkedHashMap<String, PersoVideo>()
            cached?.forEach { known[it.path] = it }
            listOfNotNull(request.start, resume).forEach { known.getOrPut(it) { PersoVideo(it) } }
            persoVideos = known
            // In name order the first video is only known from a walk, unless one is asked or resumed.
            if (known.isNotEmpty() && (request.shuffle || cached != null || request.start != null || resume != null)) {
                beginPerso(request, known.keys.toList(), complete = false)
            }
            val began = SystemClock.elapsedRealtime()
            var grown = began
            val all = try {
                videosUnder(nas, request.folder) { batch ->
                    withContext(Dispatchers.Main) {
                        batch.forEach { known.putIfAbsent(it.path, it) }
                        val now = SystemClock.elapsedRealtime()
                        val queue = queue
                        when {
                            // Shuffled: enough found to draw from, or the walk is slow.
                            queue == null && request.shuffle && (known.size >= FIRST_BATCH || now - began > FIRST_WAIT_MS) ->
                                beginPerso(request, known.keys.toList(), complete = false)
                            queue != null && now - grown > GROW_EVERY_MS -> {
                                grown = now
                                queue.keysChanged(known.keys.toList(), complete = false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (queue == null) preparing = "Lecture impossible : ${e.toUserMessage()}"
                return@launch
            }
            if (all.isEmpty()) {
                if (queue == null) preparing = "Aucune vidéo dans ce dossier."
                return@launch
            }
            // The whole folder, in name order: for the queue, and for the next time.
            persoVideos = all.associateByTo(LinkedHashMap()) { it.path }
            withContext(Dispatchers.IO) { app.persoTrees.save(request.folder, all) }
            val keys = all.map { it.path }
            queue?.keysChanged(keys, complete = true) ?: beginPerso(request, keys, complete = true)
        }
    }

    private fun beginPerso(request: PersoRequest, keys: List<String>, complete: Boolean, shuffle: Boolean = request.shuffle) {
        val state = app.perso.folder(request.folder)
        val order = PlayOrder(keys, shuffle, state.played, repeat = if (request.only) Repeat.None else Repeat.All, complete = complete)
        val first = firstOf(order, request.start, state) { app.perso.progress.value[it] }
        val built = PlayerQueue(order, ::persoItem, perso = true)
        queue = built
        start(built.begin(first))
    }

    /** A Perso video: from the device when downloaded, where it stopped. */
    private fun persoItem(path: String): PlayItem {
        val local = app.downloads.localFile(persoDownloadKey(path))
        return PlayItem(
            url = local?.local ?: app.playUrl(path),
            title = path.substringAfterLast('\\').substringBeforeLast('.'),
            subtitle = path.substringBeforeLast('\\').substringAfterLast('\\'),
            subtitles = local?.localSubtitles ?: persoVideos[path]?.subtitles.orEmpty().map(app::playUrl),
            file = path,
            start = app.perso.resumeAt(path),
            minutes = app.persoMedia.infos.value[path]?.duration?.let { (it / 60).toInt().coerceAtLeast(1) },
        )
    }

    /** ⏮: the start of this video after a few seconds of it, else the one before. */
    private fun playPrevious() {
        val queue = queue ?: return
        if (player.position.value > RESTART_AFTER) {
            player.seekTo(0.0)
            return
        }
        leaveCurrent()
        start(fresh(queue.back() ?: return))
    }

    private fun playNext() {
        val queue = queue ?: return
        leaveCurrent()
        start(fresh(queue.advance() ?: return))
    }

    /** A video of the panel, now; [fromStart]: "Reprendre au début". */
    private fun jumpTo(key: String, fromStart: Boolean) {
        val queue = queue ?: return
        leaveCurrent()
        if (fromStart && perso != null) app.perso.forget(listOf(key))
        val item = fresh(queue.jump(key))
        start(if (fromStart) item.copy(start = 0.0) else item)
    }

    /** Saved, scrobbled and measured before another video starts. */
    private fun leaveCurrent() {
        saveProgress()
        scrobbler.stop()
        recordMeasure()
    }

    /** Perso: where it stopped now, not when the queue was made. */
    private fun fresh(item: PlayItem): PlayItem = if (perso != null) persoItem(item.key) else item

    private fun updateBackground() {
        val item = current ?: return
        if (background) {
            BackgroundPlayback.title = item.title
            BackgroundPlayback.subtitle = item.subtitle
            PlaybackService.update(this)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        saveProgress()
        saveFolder()
        // The small window, or the sound alone out of sight: playback goes on.
        if (isInPictureInPictureMode) return
        val item = current
        if (settings.backgroundAudio && player.isPlaying && !isFinishing && item != null) {
            background = true
            BackgroundPlayback.title = item.title
            BackgroundPlayback.subtitle = item.subtitle
            BackgroundPlayback.playing = true
            BackgroundPlayback.toggle = { runOnUiThread { player.togglePause() } }
            BackgroundPlayback.stop = { runOnUiThread { finish() } }
            PlaybackService.start(this)
            return
        }
        scrobbler.stop()
        player.pause()
    }

    override fun onStop() {
        super.onStop()
        // No picture to draw: no video decoding.
        if (::player.isInitialized && background) player.setVideoEnabled(false)
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && background) {
            background = false
            player.setVideoEnabled(true)
            PlaybackService.stop(this)
        }
    }

    /** Home pressed while playing: the video goes on in a small window (before Android 12, which does it by itself). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && settings.pictureInPicture && ::player.isInitialized && player.isPlaying) enterPip()
    }

    private fun enterPip() {
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val (width, height) = player.videoSize() ?: (16 to 9)
        val ratio = (width.toDouble() / height).coerceIn(1 / 2.39, 2.39)
        val toggle = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_PIP_TOGGLE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = player.paused.value
        val action = RemoteAction(
            Icon.createWithResource(this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
            if (paused) "Lecture" else "Pause", if (paused) "Lecture" else "Pause", toggle,
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(listOf(action))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(settings.pictureInPicture && player.isPlaying) }
            .build()
    }

    private fun updatePip() {
        if (::player.isInitialized) runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // The small window closed: the player goes with it.
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) finish()
    }

    /** Subtitles searched online for the playing file; null without an OpenSubtitles key. */
    private fun onlineSubtitles(): OnlineSubtitlesHook? {
        if (!app.openSubtitles.status.value.configured) return null
        val languages = settings.onlineSubtitleLanguages
        return OnlineSubtitlesHook(
            languages = languages,
            status = app.openSubtitles.status,
            search = {
                val item = current ?: return@OnlineSubtitlesHook emptyList()
                val read = item.stream ?: item.file
                val hash = withContext(Dispatchers.IO) { runCatching { read?.let { app.nas?.open(it)?.use(::movieHash) } }.getOrNull() }
                val target = item.trakt
                app.openSubtitles.search(
                    SubtitleQuery(
                        hash = hash,
                        fileName = read?.substringAfterLast('\\') ?: item.title,
                        movieTmdb = target?.movieTmdb,
                        showTmdb = target?.showTmdb,
                        season = target?.season,
                        episode = target?.episode,
                    ),
                    languages,
                )
            },
            add = { subtitle ->
                val track = OnlineTrack(app.openSubtitles.download(subtitle).absolutePath, subtitle.language)
                player.addSubtitles(track)
                // Back with the video next time, without downloading it again.
                current?.file?.let { app.subtitleMemory.remember(it, track.language, track.path) }
            },
        )
    }

    private fun start(item: PlayItem) {
        started++
        saveFolder()
        (item.stream ?: item.file)?.let { (application as NyxaraApp).streamServer.resetStats(it) }
        val read = item.stream ?: item.file
        val remote = read?.let { app.nas?.hostOf(it) }?.let(::isTailnet) == true
        val online = if (perso == null) item.file?.let(app.subtitleMemory::of).orEmpty() else emptyList()
        player.play(toMpvPath(item.url), item.subtitles, item.start, online = online, remote = remote)
    }

    /** The file already measured: one measure per file. */
    private var measured = -1

    /** Keeps what this file cost to open, seek and play, for Réglages › Mesures de lecture. */
    private fun recordMeasure() {
        // Perso stays out of the measures: their titles would show there.
        if (measured == started || perso != null) return
        val item = current ?: return
        val figures = player.figures()
        if (figures.openMs == null) return // never played: nothing to measure
        measured = started
        val app = application as NyxaraApp
        val read = item.stream ?: item.file
        val stats = read?.let { app.streamServer.statsFor(it) }
        val source = read?.let { app.nas?.sourceOf(it) }
        app.measures.add(
            PlaybackMeasure(
                at = System.currentTimeMillis(),
                title = listOfNotNull(item.title, item.subtitle?.substringBefore(" · ")).joinToString(" "),
                source = source?.label ?: "Appareil",
                protocol = source?.protocol?.label ?: "Fichier",
                network = networkLabel(),
                route = read?.let { app.nas?.hostOf(it) }?.let(::routeOf) ?: "Local",
                openMs = figures.openMs,
                loadedMs = figures.loadedMs,
                seeksMs = figures.seeksMs,
                stalls = figures.stalls,
                stalledMs = figures.stalledMs,
                watchedS = figures.watchedS,
                readMbps = stats?.readMbps,
                nasOpenMs = stats?.openMs,
                requests = stats?.requests ?: 0,
                opens = stats?.opens ?: 0,
                megabytes = stats?.megabytes ?: 0,
                fileMbps = stats?.size?.takeIf { it > 0 && figures.duration > 0 }?.let { it * 8.0 / 1e6 / figures.duration },
                video = figures.video,
                droppedFrames = figures.droppedFrames,
            ),
        )
    }

    /** "Wi-Fi", "Mobile", "Wi-Fi + VPN"… */
    private fun networkLabel(): String {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return "?"
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "Hors ligne"
        val base = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> null
        }
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return listOfNotNull(base, "VPN".takeIf { vpn }).joinToString(" + ").ifEmpty { "?" }
    }

    private val scrobbler = object {
        /** Trakt's view of the current file: a pause only follows a start, nothing follows a stop but a new start. */
        private var state = "idle"

        private fun send(endpoint: TraktEndpoint) {
            val target = current?.trakt ?: return
            val duration = player.duration.value
            if (duration <= 0) return
            val percent = (player.position.value / duration * 100).coerceIn(0.0, 100.0)
            (application as NyxaraApp).trakt.scrobble(endpoint, target, percent)
        }

        fun start() {
            if (state == "playing") return
            // A pause shorter than the delay never reaches Trakt.
            if (pendingPause?.isActive == true) {
                pendingPause?.cancel()
                state = "playing"
                return
            }
            state = "playing"
            send(TraktEndpoint.ScrobbleStart)
        }

        /** Leaving the player pauses it just before it stops: only the stop is sent then. */
        private var pendingPause: Job? = null

        fun pause() {
            if (state != "playing") return
            state = "paused"
            pendingPause = lifecycleScope.launch {
                delay(1_500)
                send(TraktEndpoint.ScrobblePause)
            }
        }

        fun stop() {
            if (state != "playing" && state != "paused") return
            pendingPause?.cancel()
            send(TraktEndpoint.ScrobbleStop)
            state = "idle"
        }
    }

    private fun saveProgress() {
        val file = current?.file ?: return
        if (perso == null) {
            app.history.save(file, player.position.value, player.duration.value)
        } else {
            // Perso: its own history, never the library's nor Trakt's.
            app.perso.save(file, player.position.value, player.duration.value)
        }
    }

    /** What the Perso folder played: written when the video changes, not every 10 s (thousands of paths). */
    private fun saveFolder() {
        val request = perso?.takeIf { !it.only } ?: return
        val queue = queue ?: return
        app.perso.saveFolder(request.folder, PersoFolderState(last = queue.currentKey, played = queue.order.played))
    }

    override fun onDestroy() {
        if (background) PlaybackService.stop(this)
        BackgroundPlayback.toggle = null
        BackgroundPlayback.stop = null
        runCatching { unregisterReceiver(pipReceiver) }
        if (::player.isInitialized) {
            recordMeasure()
            player.release()
            (application as NyxaraApp).streamServer.closeIdle()
        }
        super.onDestroy()
    }

    /** mpv cannot open content:// URIs: hand it a file descriptor it will close itself. */
    private fun toMpvPath(url: String): String {
        val uri = Uri.parse(url)
        if (uri.scheme != "content") return url
        val fd = contentResolver.openFileDescriptor(uri, "r")?.detachFd() ?: return url
        return "fdclose://$fd"
    }

    companion object {
        private const val EXTRA_QUEUE = "queue"
        private const val EXTRA_PERSO = "perso"
        private const val ACTION_PIP_TOGGLE = "io.github.mkdevtests.umbra.PIP_TOGGLE"
        private const val PREFETCH_BEFORE_END = 90.0

        /** A shuffled Perso folder starts once this many videos are found, or after [FIRST_WAIT_MS]. */
        private const val FIRST_BATCH = 40
        private const val FIRST_WAIT_MS = 1_500L

        /** How often the queue takes in what the walk found meanwhile. */
        private const val GROW_EVERY_MS = 2_000L

        /** ⏮ past this many seconds: back to the start of the video, not the one before. */
        private const val RESTART_AFTER = 5.0
        private val QUEUE = ListSerializer(PlayItem.serializer())

        fun intent(context: Context, queue: List<PlayItem>): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_QUEUE, Json.encodeToString(QUEUE, queue))

        /** Plays the Perso [folder] and its subfolders, shuffled or in order, from [start] if given; [only]: [start] alone. */
        fun persoIntent(context: Context, folder: String, shuffle: Boolean, start: String? = null, only: Boolean = false): Intent =
            persoIntent(context, PersoRequest(folder, shuffle, start, only))

        fun persoIntent(context: Context, request: PersoRequest): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_PERSO, Json.encodeToString(PersoRequest.serializer(), request))

        fun intent(context: Context, url: String, title: String, subtitles: List<String> = emptyList(), file: String? = null): Intent =
            intent(context, listOf(PlayItem(url, title, subtitles = subtitles, file = file)))
    }
}

private val PanelColor = Color(0xFF14121A)
private val SPEEDS = listOf(0.75, 1.0, 1.25, 1.5, 2.0)

/** What the player needs to search subtitles online and add one. */
class OnlineSubtitlesHook(
    val languages: List<String>,
    val status: StateFlow<OpenSubtitlesStatus>,
    val search: suspend () -> List<OnlineSubtitle>,
    val add: suspend (OnlineSubtitle) -> Unit,
)

/**
 * Online subtitles of the playing file: searched once, then the best one of a
 * language in one touch, the next one if it isn't in sync, or any from the list.
 */
private class OnlineSubtitles(private val hook: OnlineSubtitlesHook, private val scope: CoroutineScope) {
    val languages get() = hook.languages
    val status get() = hook.status
    var results by mutableStateOf<List<OnlineSubtitle>?>(null)
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    /** The subtitle just added, offered to swap for the next one. */
    var added by mutableStateOf<OnlineSubtitle?>(null)
    private val tried = mutableMapOf<String, MutableSet<Int>>()

    private suspend fun found(): List<OnlineSubtitle> = results ?: hook.search().also { results = it }

    /** The best subtitle of [language] not tried yet. */
    fun best(language: String) = run("Recherche en ${subtitleLanguageName(language).lowercase()}…") {
        val candidate = found().firstOrNull { it.language == language && it.fileId !in tried[language].orEmpty() }
            ?: throw IllegalStateException(
                if (tried[language].isNullOrEmpty()) "Rien en ${subtitleLanguageName(language).lowercase()} pour cette vidéo." else "Plus d'autre choix en ${subtitleLanguageName(language).lowercase()}.",
            )
        add(candidate)
    }

    fun next() = added?.let { best(it.language) }

    fun choose(subtitle: OnlineSubtitle) = run("Téléchargement…") { add(subtitle) }

    fun hasNext(subtitle: OnlineSubtitle) =
        results.orEmpty().any { it.language == subtitle.language && it.fileId !in tried[subtitle.language].orEmpty() }

    /** Loads the list for the choice window. */
    fun load() = run("Recherche…") { found() }

    private suspend fun add(subtitle: OnlineSubtitle) {
        tried.getOrPut(subtitle.language) { mutableSetOf() } += subtitle.fileId
        hook.add(subtitle)
        added = subtitle
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = null
            }
        }
    }
}

/** A jump shown on the side of the picture; jumps in a row add up ("+1 min 30"). */
private data class SeekFlash(val seconds: Int, val at: Long)

/** Seconds before the end at which the next episode is offered, when no credits chapter says where they start. */
private const val TAIL_SECONDS = 20

@Composable
private fun PlayerScreen(
    player: MpvPlayer,
    item: PlayItem,
    queue: PlayerQueue,
    settings: Settings,
    inPip: Boolean,
    onlineHook: OnlineSubtitlesHook?,
    onNext: () -> Unit,
    onPip: () -> Unit,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    /** A video of the queue panel now; true: from its start. */
    onJump: (String, Boolean) -> Unit,
) {
    // A Perso video: no next-episode countdown, the next one starts at once.
    val perso = queue.perso
    val next = queue.upNext
    // Previous, next, shuffle, repeat and the queue: only with more than one video.
    val many = queue.size > 1
    val position by player.position.collectAsState()
    val duration by player.duration.collectAsState()
    val paused by player.paused.collectAsState()
    val buffering by player.buffering.collectAsState()
    val speed by player.speed.collectAsState()
    val fill by player.fill.collectAsState()
    val ended by player.ended.collectAsState()
    val chapters by player.chapters.collectAsState()
    val audioTracks by player.audioTracks.collectAsState()
    val subtitleTracks by player.subtitleTracks.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<SeekFlash?>(null) }
    // A swipe across the picture under way: the time it would land on.
    var swipeTarget by remember { mutableStateOf<Double?>(null) }
    val scope = rememberCoroutineScope()
    val online = remember(item, onlineHook) { onlineHook?.let { OnlineSubtitles(it, scope) } }
    var choosing by remember { mutableStateOf(false) }
    // No subtitles in the profile's language in the file: offered once to look online.
    var offerOnline by remember(item) { mutableStateOf<String?>(null) }
    LaunchedEffect(item, audioTracks.isNotEmpty()) {
        if (online == null || audioTracks.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val wanted = settings.subtitles ?: return@LaunchedEffect
        val code = ONLINE_CODES[wanted]?.takeIf { it in online.languages } ?: return@LaunchedEffect
        val heard = audioTracks.firstOrNull { it.selected }?.language
        if (wanted.matches(heard)) return@LaunchedEffect
        if (player.subtitleTracks.value.none { wanted.matches(it.language) }) {
            // Fetched by itself when asked (Réglages), else offered.
            if (settings.autoOnlineSubtitles) online.best(code) else offerOnline = code
        }
    }
    LaunchedEffect(online?.added) {
        if (online?.added != null) {
            delay(12_000)
            online.added = null
        }
    }
    LaunchedEffect(offerOnline) {
        if (offerOnline != null) {
            delay(12_000)
            offerOnline = null
        }
    }
    var showInfo by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    // While dragging, the slider shows the finger position, not mpv's.
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    // The next-episode countdown, dismissed for the current file only.
    var nextCancelled by remember(item) { mutableStateOf(false) }
    // Intro or credits skipped by themselves: once per chapter, a seek back plays it.
    val autoSkipped = remember(item) { mutableSetOf<Int>() }
    val skip = skippableAt(chapters, position, duration)

    /** Jumps [seconds] (negative: back); jumps in a row add up on the screen. */
    fun jump(seconds: Int) {
        player.seekBy(seconds)
        val now = SystemClock.uptimeMillis()
        val previous = flash
        val total = if (previous != null && now - previous.at < 1_200 && previous.seconds.sign == seconds.sign) previous.seconds + seconds else seconds
        flash = SeekFlash(total, now)
    }

    /** Held ⏪ or ⏩: jumps again and again, further and further, until released. */
    fun holdJump(direction: Int): Job = scope.launch {
        val start = SystemClock.uptimeMillis()
        delay(HOLD_DELAY_MS)
        while (true) {
            jump(direction * holdStep(SystemClock.uptimeMillis() - start))
            delay(HOLD_TICK_MS)
        }
    }

    LaunchedEffect(controlsVisible, paused, panelOpen, queueOpen, speedMenu, swipeTarget) {
        if (controlsVisible && !paused && !panelOpen && !queueOpen && !speedMenu && swipeTarget == null) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(900)
            flash = null
        }
    }
    LaunchedEffect(showInfo) {
        while (showInfo) {
            info = player.debugInfo()
            delay(1_000)
        }
    }
    LaunchedEffect(ended) {
        if (ended && next == null) controlsVisible = true
    }
    LaunchedEffect(skip?.index, item) {
        val current = skip ?: return@LaunchedEffect
        if (!settings.autoSkip || current.index in autoSkipped) return@LaunchedEffect
        val skipCredits = current.kind == ChapterKind.Credits && next == null && !current.last
        if (current.kind == ChapterKind.Intro || skipCredits) {
            autoSkipped += current.index
            player.seekTo(current.end)
        }
    }

    // The remote (Android TV) or a keyboard: ⏪ ⏩ and pause without the controls, the controls on any other key.
    val keys = remember { FocusRequester() }
    LaunchedEffect(controlsVisible, panelOpen, queueOpen) { if (!controlsVisible && !panelOpen && !queueOpen) runCatching { keys.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(keys)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.Spacebar -> { player.togglePause(); true }
                    Key.MediaPlay -> { if (player.paused.value) player.togglePause(); true }
                    Key.MediaPause -> { if (!player.paused.value) player.togglePause(); true }
                    Key.MediaFastForward -> { jump(REMOTE_LONG_JUMP); true }
                    Key.MediaRewind -> { jump(-REMOTE_LONG_JUMP); true }
                    Key.DirectionLeft -> if (!controlsVisible && !panelOpen && !queueOpen) { jump(-SHORT_JUMP); true } else false
                    Key.DirectionRight -> if (!controlsVisible && !panelOpen && !queueOpen) { jump(SHORT_JUMP); true } else false
                    Key.DirectionCenter, Key.Enter -> if (!controlsVisible && !panelOpen && !queueOpen) { player.togglePause(); controlsVisible = true; true } else false
                    Key.DirectionUp, Key.DirectionDown, Key.Menu -> if (!controlsVisible && !panelOpen && !queueOpen) { controlsVisible = true; true } else false
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        when {
                            panelOpen -> panelOpen = false
                            queueOpen -> queueOpen = false
                            else -> controlsVisible = !controlsVisible
                        }
                    },
                    // Double tap on a side: jump back or ahead; in the middle: pause.
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width / 3f -> jump(-SHORT_JUMP)
                            offset.x > size.width * 2f / 3f -> jump(SHORT_JUMP)
                            else -> player.togglePause()
                        }
                    },
                )
            }
            // A swipe across the picture: seconds for a short one, minutes for a long one; applied on release.
            .pointerInput(Unit) {
                var from = 0.0
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        from = player.position.value
                        dragged = 0f
                        // Not while the track panel is open: its rows are under the finger.
                        swipeTarget = from.takeIf { !panelOpen && !queueOpen }
                    },
                    onDragEnd = {
                        swipeTarget?.let { target -> if (abs(target - from) >= 1) player.seekTo(target) }
                        swipeTarget = null
                    },
                    onDragCancel = { swipeTarget = null },
                    onHorizontalDrag = { change, amount ->
                        if (swipeTarget == null) return@detectHorizontalDragGestures
                        change.consume()
                        dragged += amount
                        val end = player.duration.value.takeIf { it > 0 } ?: Double.MAX_VALUE
                        swipeTarget = (from + swipeSeconds(dragged / size.width)).coerceIn(0.0, end)
                    },
                )
            },
    ) {
        AndroidView(
            factory = { context -> SurfaceView(context).apply { holder.addCallback(player) } },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(if (inPip) 24.dp else 48.dp))
        }
        if (inPip) return@Box

        if (showInfo) {
            Text(
                text = info,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(8.dp),
            )
        }

        swipeTarget?.let { target ->
            val delta = (target - position).toInt()
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 28.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(formatTime(target), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Text((if (delta < 0) "−" else "+") + durationLabel(delta), color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
            }
        }

        flash?.let {
            Text(
                (if (it.seconds < 0) "⏪  −" else "+") + durationLabel(it.seconds) + if (it.seconds > 0) "  ⏩" else "",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(if (it.seconds < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 72.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().safeDrawingPadding().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("←", color = Color.White, fontSize = 24.sp) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        item.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1) }
                    }
                    IconButton(onClick = onPip) { Icon(NyxaraIcons.Pip, contentDescription = "Image dans l'image", tint = Color.White) }
                    TextButton(onClick = { showInfo = !showInfo }) { Text("Infos", color = Color.White) }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (many) SkipButton(forward = false, onClick = onPrevious)
                    SeekButton(-1, onJump = ::jump, onHold = ::holdJump)
                    Surface(
                        onClick = { player.togglePause() },
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.size(88.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (paused) "▶" else "❚❚", color = Color.White, fontSize = 34.sp)
                        }
                    }
                    SeekButton(1, onJump = ::jump, onHold = ::holdJump)
                    if (many) SkipButton(forward = true, enabled = next != null || queue.stopAfter, onClick = onNext)
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = dragPosition?.toDouble() ?: position
                        Text(formatTime(shown), color = Color.White, fontSize = 13.sp)
                        Slider(
                            value = (dragPosition ?: position.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(0f)),
                            onValueChange = { dragPosition = it },
                            onValueChangeFinished = {
                                dragPosition?.let { player.seekTo(it.toDouble()) }
                                dragPosition = null
                            },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text("−" + formatTime(duration - shown), color = Color.White, fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Audio et sous-titres", active = panelOpen) { queueOpen = false; panelOpen = !panelOpen }
                        Box {
                            Pill("Vitesse ${formatSpeed(speed)}", active = speed != 1.0) { speedMenu = true }
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                SPEEDS.forEach { choice ->
                                    DropdownMenuItem(
                                        text = { Text((if (choice == speed) "✓ " else "    ") + formatSpeed(choice)) },
                                        onClick = {
                                            player.setSpeed(choice)
                                            speedMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Pill(if (fill) "Format : remplir" else "Format : entier", active = fill) { player.toggleFill() }
                        Spacer(Modifier.weight(1f))
                        if (many) {
                            // Read the version: the buttons follow the queue's changes.
                            queue.version
                            Pill("Aléatoire", active = queue.shuffle, onClick = queue::toggleShuffle)
                            Pill("Répéter : ${queue.repeat.label}", active = queue.repeat != Repeat.None, onClick = queue::cycleRepeat)
                            Pill("File d'attente · ${queue.size}", active = queueOpen) { panelOpen = false; queueOpen = !queueOpen }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            TrackPanel(player, settings, online) { choosing = true; online?.load() }
        }

        AnimatedVisibility(
            visible = queueOpen,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            QueuePanel(queue, position, duration, onJump = onJump, onClose = { queueOpen = false })
        }

        // What happened online: added (in sync? the next one), searching, a failure, or an offer.
        val banner = online?.let { state ->
            when {
                state.busy != null -> Banner(state.busy!!)
                state.error != null -> Banner(state.error!!, actions = listOf("OK" to { state.error = null }))
                state.added != null -> state.added!!.let { added ->
                    Banner(
                        "Sous-titres ${subtitleLanguageName(added.language).lowercase()} ajoutés" + if (added.hashMatch) " · faits pour ce fichier" else "",
                        actions = listOfNotNull(
                            ("Décalés ? Essayer le suivant" to { state.next(); Unit }).takeIf { state.hasNext(added) },
                            "OK" to { state.added = null },
                        ),
                    )
                }
                offerOnline != null -> offerOnline!!.let { code ->
                    Banner(
                        "Pas de sous-titres ${subtitleLanguageName(code).lowercase()} dans ce fichier",
                        actions = listOf("Chercher en ligne" to { offerOnline = null; state.best(code) }, "Non merci" to { offerOnline = null }),
                    )
                }
                else -> null
            }
        }
        banner?.let { OnlineBanner(it, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 72.dp)) }

        if (choosing && online != null) OnlineChoices(online) { choosing = false }

        // The next episode: at the credits, in the last seconds, or at the end.
        val credits = skip?.kind == ChapterKind.Credits
        val tail = !hasCredits(chapters, duration) && duration > 300 && position > 0 && duration - position <= TAIL_SECONDS
        if (!perso && next != null && queue.repeat != Repeat.One && !nextCancelled && (ended || (settings.nextEpisodeCountdown && (credits || tail)))) {
            NextUp(
                next = next,
                seconds = when {
                    ended -> 8
                    credits -> 10
                    else -> (duration - position).toInt().coerceAtLeast(1)
                },
                onPlay = onNext,
                onCancel = { nextCancelled = true; controlsVisible = true },
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(32.dp),
            )
        } else if (skip != null && !(skip.kind == ChapterKind.Credits && (skip.last || (next != null && !perso)))) {
            // Intro, or credits followed by more of the film: a button jumps past them.
            Pill(
                if (skip.kind == ChapterKind.Intro) "Passer l'intro  ⏭" else "Passer le générique  ⏭",
                active = true,
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 32.dp, bottom = 110.dp),
            ) { player.seekTo(skip.end) }
        }
    }
}

/** ⏪ or ⏩: a tap jumps 10 s, holding it goes on further and further until released. */
@Composable
private fun SeekButton(direction: Int, onJump: (Int) -> Unit, onHold: (Int) -> Job) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .pointerInput(direction) {
                detectTapGestures(
                    onPress = {
                        val holding = onHold(direction)
                        val started = SystemClock.uptimeMillis()
                        tryAwaitRelease()
                        holding.cancel()
                        // Released before the repeat began: a single jump.
                        if (SystemClock.uptimeMillis() - started < HOLD_DELAY_MS) onJump(direction * SHORT_JUMP)
                    },
                )
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (direction < 0) "⏪ 10" else "10 ⏩", color = Color.White, fontSize = 22.sp)
    }
}

/** ⏩ ⏪ of a remote: further than a double tap. */
private const val REMOTE_LONG_JUMP = 30

/** Held ⏪ ⏩: the first repeat after this delay, then one jump per tick. */
private const val HOLD_DELAY_MS = 450L
private const val HOLD_TICK_MS = 350L


@Composable
private fun Pill(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (active) Color.White else Color.White.copy(alpha = 0.14f),
        contentColor = if (active) Color.Black else Color.White,
    ) {
        Text(text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** Side panel: audio and subtitle tracks, their delays, subtitles found online. */
@Composable
private fun TrackPanel(player: MpvPlayer, settings: Settings, online: OnlineSubtitles?, onChooseOnline: () -> Unit) {
    val audio by player.audioTracks.collectAsState()
    val subtitles by player.subtitleTracks.collectAsState()
    val delay by player.subtitleDelay.collectAsState()
    val audioDelay by player.audioDelay.collectAsState()
    val subtitlesOn = subtitles.any { it.selected }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(420.dp)
            .background(PanelColor)
            // Taps inside the panel must not close it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        PanelTitle("Audio")
        if (audio.isEmpty()) PanelNote("Aucune piste audio")
        audio.forEach { track -> TrackRow(track.label, track.detail, track.selected) { player.selectAudio(track.id) } }

        PanelTitle("Sous-titres")
        subtitles.forEach { track -> TrackRow(track.label, track.detail, track.selected) { player.selectSubtitles(track.id) } }
        TrackRow("Désactivés", null, !subtitlesOn) { player.selectSubtitles(NO_SUBTITLES) }

        if (online != null) {
            PanelTitle("Sous-titres en ligne")
            online.languages.forEach { code ->
                TrackRow("Ajouter le meilleur en ${subtitleLanguageName(code).lowercase()}", "Choisi pour ce fichier, activé tout de suite", false) { online.best(code) }
            }
            TrackRow("Voir tous les choix…", quota(online.status.collectAsState().value), false, onClick = onChooseOnline)
        }

        PanelTitle("Son et image")
        val night by player.nightAudio.collectAsState()
        val anime by player.animeUpscale.collectAsState()
        TrackRow("Mode nuit", "Dialogues plus forts, explosions plus douces", night) { player.setNightAudio(!night) }
        TrackRow("Amélioration anime (Anime4K)", "Traits plus nets sur les dessins animés ; plus de travail pour la tablette", anime) { player.setAnimeUpscale(!anime) }

        PanelTitle("Décalage sous-titres")
        DelayRow(delay, enabled = subtitlesOn) { player.shiftSubtitles(it) }
        PanelNote("Positif : les sous-titres arrivent plus tard.")

        PanelTitle("Décalage audio")
        DelayRow(audioDelay, enabled = audio.isNotEmpty(), stepSeconds = 0.05) { player.shiftAudio(it) }
        PanelNote("Positif : le son arrive plus tard (image en avance sur le son : augmenter).")

        Spacer(Modifier.size(16.dp))
        PanelNote(profileSummary(settings))
    }
}

/** − value +, and back to 0. */
@Composable
private fun DelayRow(value: Double, enabled: Boolean, stepSeconds: Double = 0.1, onShift: (Double) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = { onShift(-stepSeconds) }, enabled = enabled) { Text("−", color = Color.White) }
        Text(
            (if (stepSeconds < 0.1) "%+.2f s" else "%+.1f s").format(value).replace('.', ',').replace(Regex("""^\+0,0+ s$"""), "0 s"),
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        OutlinedButton(onClick = { onShift(stepSeconds) }, enabled = enabled) { Text("+", color = Color.White) }
        if (abs(value) > 0.001) TextButton(onClick = { onShift(-value) }) { Text("Remettre à 0") }
    }
}


@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun PanelNote(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun TrackRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.sp)
            if (!detail.isNullOrEmpty()) Text(detail, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
    }
}

/** ⏮ / ⏭ on each side of the jumps. */
@Composable
private fun SkipButton(forward: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(
            if (forward) NyxaraIcons.SkipNext else NyxaraIcons.SkipPrevious,
            contentDescription = if (forward) "Vidéo suivante" else "Vidéo précédente",
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(34.dp),
        )
    }
}

/** A Perso folder being walked before it plays, or why it can't. */
@Composable
private fun Preparing(text: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (text == "Préparation…") CircularProgressIndicator(color = Color.White)
            Text(text, color = Color.White, fontSize = 16.sp)
            TextButton(onClick = onBack) { Text("Fermer") }
        }
    }
}

/** End of an episode: the next one starts after a short countdown, unless cancelled. */
@Composable
private fun NextUp(next: PlayItem, seconds: Int, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var remaining by remember(next) { mutableIntStateOf(seconds) }
    LaunchedEffect(next) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        onPlay()
    }
    Surface(color = PanelColor, shape = RoundedCornerShape(16.dp), modifier = modifier.widthIn(max = 420.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Épisode suivant dans $remaining s", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Text(next.subtitle ?: next.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Lire maintenant", active = true, onClick = onPlay)
                Pill("Annuler", active = false, onClick = onCancel)
            }
        }
    }
}

private fun profileSummary(settings: Settings): String {
    val audio = settings.audioOrder.joinToString(", puis ") { if (it.language == null) it.label else it.label.lowercase() }
    val subtitles = settings.subtitles?.let { "sous-titres ${it.label.lowercase()} non forcés" } ?: "sans sous-titres"
    return "Choisi par ton profil : audio $audio ; $subtitles. Modifiable dans Réglages."
}

private fun formatSpeed(speed: Double) =
    (if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString().replace('.', ',')) + "×"

private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The profile's subtitle language as OpenSubtitles names it. */
private val ONLINE_CODES = mapOf(Language.French to "fr", Language.English to "en")

private class Banner(val text: String, val actions: List<Pair<String, () -> Unit>> = emptyList())

@Composable
private fun OnlineBanner(banner: Banner, modifier: Modifier) {
    Surface(color = PanelColor, shape = RoundedCornerShape(16.dp), modifier = modifier.widthIn(max = 640.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (banner.actions.isEmpty()) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(banner.text, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
            banner.actions.forEachIndexed { i, (label, action) -> Pill(label, active = i == 0, onClick = action) }
        }
    }
}

/** "4 téléchargements restants aujourd'hui", what the account allows, or nothing yet. */
private fun quota(status: OpenSubtitlesStatus): String? = when {
    status.remaining != null -> "${status.remaining} téléchargement${if (status.remaining > 1) "s" else ""} restant${if (status.remaining > 1) "s" else ""} aujourd'hui"
    status.allowed != null -> "${status.allowed} téléchargements par jour"
    else -> "OpenSubtitles"
}

/** Every subtitle found, by language: the best three, then the rest on demand. */
@Composable
private fun OnlineChoices(online: OnlineSubtitles, onClose: () -> Unit) {
    val status by online.status.collectAsState()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = PanelColor, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.9f)) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Row(modifier = Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sous-titres en ligne", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        quota(status)?.let { Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp) }
                    }
                    TextButton(onClick = onClose) { Text("Fermer") }
                }
                val results = online.results
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    when {
                        results == null && online.error != null -> PanelNote("Erreur : ${online.error}")
                        results == null -> PanelNote("Recherche…")
                    }
                    results?.let { found ->
                        online.languages.forEach { code ->
                            val ofLanguage = found.filter { it.language == code }
                            var all by remember(code) { mutableStateOf(false) }
                            PanelTitle(subtitleLanguageName(code) + "  ·  ${ofLanguage.size}")
                            if (ofLanguage.isEmpty()) PanelNote("Rien dans cette langue pour cette vidéo.")
                            (if (all) ofLanguage else ofLanguage.take(3)).forEach { subtitle ->
                                ChoiceRow(subtitle) { online.choose(subtitle); onClose() }
                            }
                            if (!all && ofLanguage.size > 3) TrackRow("Voir les ${ofLanguage.size - 3} autres", null, false) { all = true }
                        }
                    }
                }
            }
        }
    }
}

/** One subtitle: what makes it likely in sync, then its release name and how popular it is. */
@Composable
private fun ChoiceRow(subtitle: OnlineSubtitle, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                subtitle.hashMatch -> Label("✓ Synchronisé avec ce fichier", highlight = true)
                subtitle.sameRelease -> Label("Même version que ce fichier", highlight = true)
            }
            if (subtitle.hearingImpaired) Label("Malentendants")
            if (subtitle.machineTranslated) Label("Traduction automatique")
            Text("↓ " + formatCount(subtitle.downloads), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        Text(subtitle.release, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Label(text: String, highlight: Boolean = false) {
    Text(
        text,
        color = if (highlight) Color.Black else Color.White,
        fontSize = 12.sp,
        modifier = Modifier
            .background(if (highlight) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** 950, "12 k", "1,2 M". */
private fun formatCount(count: Int) = when {
    count >= 1_000_000 -> "%.1f M".format(count / 1e6).replace('.', ',')
    count >= 1_000 -> "${count / 1_000} k"
    else -> count.toString()
}
