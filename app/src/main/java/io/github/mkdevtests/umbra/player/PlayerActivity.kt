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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.settings.NO_SUBTITLES
import io.github.mkdevtests.umbra.settings.SEEK_STEPS
import io.github.mkdevtests.umbra.settings.SUBTITLE_LANGUAGES
import io.github.mkdevtests.umbra.settings.seekStepLabel
import io.github.mkdevtests.umbra.subtitles.OnlineSubtitle
import io.github.mkdevtests.umbra.subtitles.SubtitleQuery
import io.github.mkdevtests.umbra.subtitles.movieHash
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.settings.Settings
import io.github.mkdevtests.umbra.trakt.TraktEndpoint
import io.github.mkdevtests.umbra.ui.theme.NyxaraTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.sign

/** Full-screen player. Plays the queue in [EXTRA_QUEUE]: a film, or an episode and the ones after it. */
class PlayerActivity : ComponentActivity() {

    private lateinit var player: MpvPlayer
    private var queue: List<PlayItem> = emptyList()
    private var index by mutableIntStateOf(0)
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

        queue = intent.getStringExtra(EXTRA_QUEUE)?.let { Json.decodeFromString(QUEUE, it) }.orEmpty()
        if (queue.isEmpty()) return finish()
        player = MpvPlayer(this, settings)
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        start(queue[0])
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
                    // Out of sight, nobody sees the countdown: the next episode starts at once.
                    if ((background || inPip) && index + 1 < queue.size) playNext()
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
                PlayerScreen(
                    player = player,
                    item = queue[index],
                    next = queue.getOrNull(index + 1),
                    settings = settings,
                    inPip = inPip,
                    online = onlineSubtitles(),
                    onSeekStep = { step -> app.settings.update { it.copy(seekStep = step) } },
                    onNext = ::playNext,
                    onPip = ::enterPip,
                    onBack = ::finish,
                )
            }
        }
    }

    private fun playNext() {
        saveProgress()
        scrobbler.stop()
        recordMeasure()
        index++
        start(queue[index])
        if (background) {
            BackgroundPlayback.title = queue[index].title
            BackgroundPlayback.subtitle = queue[index].subtitle
            PlaybackService.update(this)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        saveProgress()
        // The small window, or the sound alone out of sight: playback goes on.
        if (isInPictureInPictureMode) return
        if (settings.backgroundAudio && player.isPlaying && !isFinishing) {
            background = true
            BackgroundPlayback.title = queue[index].title
            BackgroundPlayback.subtitle = queue[index].subtitle
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
            search = {
                val item = queue[index]
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
            add = { subtitle -> player.addSubtitles(app.openSubtitles.download(subtitle).absolutePath) },
        )
    }

    private fun start(item: PlayItem) {
        (item.stream ?: item.file)?.let { (application as NyxaraApp).streamServer.resetStats(it) }
        player.play(toMpvPath(item.url), item.subtitles, item.start)
    }

    /** The queue index already measured: one measure per file. */
    private var measured = -1

    /** Keeps what this file cost to open, seek and play, for Réglages › Mesures de lecture. */
    private fun recordMeasure() {
        if (measured == index) return
        val item = queue.getOrNull(index) ?: return
        val figures = player.figures()
        if (figures.openMs == null) return // never played: nothing to measure
        measured = index
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
            val target = queue.getOrNull(index)?.trakt ?: return
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
        val file = queue.getOrNull(index)?.file ?: return
        (application as NyxaraApp).history.save(file, player.position.value, player.duration.value)
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
        private const val ACTION_PIP_TOGGLE = "io.github.mkdevtests.umbra.PIP_TOGGLE"
        private val QUEUE = ListSerializer(PlayItem.serializer())

        fun intent(context: Context, queue: List<PlayItem>): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_QUEUE, Json.encodeToString(QUEUE, queue))

        fun intent(context: Context, url: String, title: String, subtitles: List<String> = emptyList(), file: String? = null): Intent =
            intent(context, listOf(PlayItem(url, title, subtitles = subtitles, file = file)))
    }
}

private val PanelColor = Color(0xFF14121A)
private val SPEEDS = listOf(0.75, 1.0, 1.25, 1.5, 2.0)

/** What the player needs to search subtitles online and add one. */
class OnlineSubtitlesHook(
    val languages: List<String>,
    val search: suspend () -> List<OnlineSubtitle>,
    val add: suspend (OnlineSubtitle) -> Unit,
)

/** A jump shown on the side of the picture; jumps in a row add up ("+1 min 30"). */
private data class SeekFlash(val seconds: Int, val at: Long)

/** Seconds before the end at which the next episode is offered, when no credits chapter says where they start. */
private const val TAIL_SECONDS = 20

@Composable
private fun PlayerScreen(
    player: MpvPlayer,
    item: PlayItem,
    next: PlayItem?,
    settings: Settings,
    inPip: Boolean,
    online: OnlineSubtitlesHook?,
    onSeekStep: (Int) -> Unit,
    onNext: () -> Unit,
    onPip: () -> Unit,
    onBack: () -> Unit,
) {
    val position by player.position.collectAsState()
    val duration by player.duration.collectAsState()
    val paused by player.paused.collectAsState()
    val buffering by player.buffering.collectAsState()
    val speed by player.speed.collectAsState()
    val fill by player.fill.collectAsState()
    val ended by player.ended.collectAsState()
    val chapters by player.chapters.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var stepMenu by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(settings.seekStep) }
    var flash by remember { mutableStateOf<SeekFlash?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    // While dragging, the slider shows the finger position, not mpv's.
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    // The next-episode countdown, dismissed for the current file only.
    var nextCancelled by remember(item) { mutableStateOf(false) }
    // Intro or credits skipped by themselves: once per chapter, a seek back plays it.
    val autoSkipped = remember(item) { mutableSetOf<Int>() }
    val skip = skippableAt(chapters, position, duration)

    fun jump(direction: Int) {
        player.seekBy(direction * step)
        val now = SystemClock.uptimeMillis()
        val previous = flash
        val total = if (previous != null && now - previous.at < 1_200 && previous.seconds.sign == direction) previous.seconds + direction * step else direction * step
        flash = SeekFlash(total, now)
    }

    LaunchedEffect(controlsVisible, paused, panelOpen, speedMenu, stepMenu) {
        if (controlsVisible && !paused && !panelOpen && !speedMenu && !stepMenu) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (panelOpen) panelOpen = false else controlsVisible = !controlsVisible },
                    // Double tap on a side: jump back or ahead; in the middle: pause.
                    onDoubleTap = { offset ->
                        when {
                            offset.x < size.width / 3f -> jump(-1)
                            offset.x > size.width * 2f / 3f -> jump(1)
                            else -> player.togglePause()
                        }
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

        flash?.let {
            Text(
                (if (it.seconds < 0) "⏪  −" else "+") + durationLabel(abs(it.seconds)) + if (it.seconds > 0) "  ⏩" else "",
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

                Box(modifier = Modifier.align(Alignment.Center)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SeekButton(-step, onClick = { jump(-1) }, onLongClick = { stepMenu = true })
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
                        SeekButton(step, onClick = { jump(1) }, onLongClick = { stepMenu = true })
                    }
                    // The jump's length: a long press on ⏪ or ⏩, remembered for the next videos.
                    DropdownMenu(expanded = stepMenu, onDismissRequest = { stepMenu = false }) {
                        Text("Saut", fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        SEEK_STEPS.forEach { choice ->
                            DropdownMenuItem(
                                text = { Text((if (choice == step) "✓ " else "    ") + "± " + seekStepLabel(choice)) },
                                onClick = {
                                    step = choice
                                    onSeekStep(choice)
                                    stepMenu = false
                                },
                            )
                        }
                    }
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
                        Pill("Audio et sous-titres", active = panelOpen) { panelOpen = !panelOpen }
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
                        if (next != null) Pill("Épisode suivant ›", active = false, onClick = onNext)
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
            TrackPanel(player, settings, online)
        }

        // The next episode: at the credits, in the last seconds, or at the end.
        val credits = skip?.kind == ChapterKind.Credits
        val tail = !hasCredits(chapters, duration) && duration > 300 && position > 0 && duration - position <= TAIL_SECONDS
        if (next != null && !nextCancelled && (ended || (settings.nextEpisodeCountdown && (credits || tail)))) {
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
        } else if (skip != null && !(skip.kind == ChapterKind.Credits && (skip.last || next != null))) {
            // Intro, or credits followed by more of the film: a button jumps past them.
            Pill(
                if (skip.kind == ChapterKind.Intro) "Passer l'intro  ⏭" else "Passer le générique  ⏭",
                active = true,
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 32.dp, bottom = 110.dp),
            ) { player.seekTo(skip.end) }
        }
    }
}

/** ⏪ or ⏩ with its length; a long press changes the length. */
@Composable
private fun SeekButton(seconds: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text((if (seconds < 0) "⏪ −" else "+") + seekStepLabel(abs(seconds)) + if (seconds > 0) " ⏩" else "", color = Color.White, fontSize = 20.sp)
    }
}

/** "45 s", "1 min", "1 min 30". */
private fun durationLabel(seconds: Int) = when {
    seconds < 60 -> "$seconds s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${"%02d".format(seconds % 60)}"
}

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
private fun TrackPanel(player: MpvPlayer, settings: Settings, online: OnlineSubtitlesHook?) {
    val audio by player.audioTracks.collectAsState()
    val subtitles by player.subtitleTracks.collectAsState()
    val delay by player.subtitleDelay.collectAsState()
    val audioDelay by player.audioDelay.collectAsState()
    val subtitlesOn = subtitles.any { it.selected }
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<OnlineSubtitle>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

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
            val found = results
            if (found == null) {
                TrackRow(
                    if (searching) "Recherche en ligne…" else "Chercher en ligne…",
                    "OpenSubtitles · " + online.languages.joinToString(", ") { subtitleLanguageName(it) },
                    false,
                ) {
                    if (searching) return@TrackRow
                    searching = true
                    error = null
                    scope.launch {
                        runCatching { online.search() }
                            .onSuccess { results = it }
                            .onFailure { error = it.message ?: it.toString() }
                        searching = false
                    }
                }
            } else {
                PanelTitle("En ligne · OpenSubtitles")
                if (found.isEmpty()) PanelNote("Rien dans ces langues pour ce titre.")
                found.take(MAX_ONLINE).forEach { subtitle ->
                    val label = subtitleLanguageName(subtitle.language) + if (subtitle.hashMatch) "  ·  ✓ fait pour ce fichier" else ""
                    val detail = listOfNotNull(
                        subtitle.release.take(80),
                        "${subtitle.downloads} téléchargements",
                        "malentendants".takeIf { subtitle.hearingImpaired },
                        "traduction automatique".takeIf { subtitle.machineTranslated },
                        "téléchargement…".takeIf { adding == subtitle.fileId },
                    ).joinToString(" · ")
                    TrackRow(label, detail, false) {
                        if (adding != null) return@TrackRow
                        adding = subtitle.fileId
                        error = null
                        scope.launch {
                            runCatching { online.add(subtitle) }
                                .onSuccess { results = null }
                                .onFailure { error = it.message ?: it.toString() }
                            adding = null
                        }
                    }
                }
                TrackRow("Fermer la recherche", null, false) { results = null }
            }
            error?.let { PanelNote("Erreur : $it") }
        }

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

private const val MAX_ONLINE = 30

/** "fr" → "Français", "pt-BR" → "Portugais (Brésil)". */
private fun subtitleLanguageName(code: String): String =
    SUBTITLE_LANGUAGES[code] ?: java.util.Locale.forLanguageTag(code).getDisplayName(java.util.Locale.FRENCH).replaceFirstChar { it.uppercase() }.ifEmpty { code }

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
