package io.github.mkdevtests.umbra.player

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.mkdevtests.umbra.UmbraApp
import io.github.mkdevtests.umbra.settings.NO_SUBTITLES
import io.github.mkdevtests.umbra.settings.Settings
import io.github.mkdevtests.umbra.trakt.TraktEndpoint
import io.github.mkdevtests.umbra.ui.theme.UmbraTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Full-screen player. Plays the queue in [EXTRA_QUEUE]: a film, or an episode and the ones after it. */
class PlayerActivity : ComponentActivity() {

    private lateinit var player: MpvPlayer
    private var queue: List<PlayItem> = emptyList()
    private var index by mutableIntStateOf(0)

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
        val settings = (application as UmbraApp).settings.settings.value
        player = MpvPlayer(this, settings)
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
            UmbraTheme {
                PlayerScreen(
                    player = player,
                    item = queue[index],
                    next = queue.getOrNull(index + 1),
                    settings = settings,
                    onNext = {
                        saveProgress()
                        scrobbler.stop()
                        recordMeasure()
                        index++
                        start(queue[index])
                    },
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) {
            scrobbler.stop()
            player.pause()
            saveProgress()
        }
    }

    private fun start(item: PlayItem) {
        item.file?.let { (application as UmbraApp).streamServer.resetStats(it) }
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
        val app = application as UmbraApp
        val stats = item.file?.let { app.streamServer.statsFor(it) }
        val source = item.file?.let { app.nas?.sourceOf(it) }
        app.measures.add(
            PlaybackMeasure(
                at = System.currentTimeMillis(),
                title = listOfNotNull(item.title, item.subtitle?.substringBefore(" · ")).joinToString(" "),
                source = source?.label ?: "Appareil",
                network = networkLabel(),
                route = source?.host?.let(::routeOf) ?: "Local",
                openMs = figures.openMs,
                seeksMs = figures.seeksMs,
                stalls = figures.stalls,
                stalledMs = figures.stalledMs,
                watchedS = figures.watchedS,
                readMbps = stats?.readMbps,
                nasOpenMs = stats?.openMs,
                requests = stats?.requests ?: 0,
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
            (application as UmbraApp).trakt.scrobble(endpoint, target, percent)
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
        (application as UmbraApp).history.save(file, player.position.value, player.duration.value)
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            recordMeasure()
            player.release()
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
        private val QUEUE = ListSerializer(PlayItem.serializer())

        fun intent(context: Context, queue: List<PlayItem>): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_QUEUE, Json.encodeToString(QUEUE, queue))

        fun intent(context: Context, url: String, title: String, subtitles: List<String> = emptyList(), file: String? = null): Intent =
            intent(context, listOf(PlayItem(url, title, subtitles = subtitles, file = file)))
    }
}

private val PanelColor = Color(0xFF14121A)
private val SPEEDS = listOf(0.75, 1.0, 1.25, 1.5, 2.0)

@Composable
private fun PlayerScreen(
    player: MpvPlayer,
    item: PlayItem,
    next: PlayItem?,
    settings: Settings,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val position by player.position.collectAsState()
    val duration by player.duration.collectAsState()
    val paused by player.paused.collectAsState()
    val buffering by player.buffering.collectAsState()
    val speed by player.speed.collectAsState()
    val fill by player.fill.collectAsState()
    val ended by player.ended.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    // While dragging, the slider shows the finger position, not mpv's.
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    // The end-of-episode countdown, dismissed for the current file only.
    var nextCancelled by remember(item) { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, paused, panelOpen, speedMenu) {
        if (controlsVisible && !paused && !panelOpen && !speedMenu) {
            delay(4_000)
            controlsVisible = false
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (panelOpen) panelOpen = false else controlsVisible = !controlsVisible
            },
    ) {
        AndroidView(
            factory = { context -> SurfaceView(context).apply { holder.addCallback(player) } },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(48.dp))
        }

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
                    TextButton(onClick = { showInfo = !showInfo }) { Text("Infos", color = Color.White) }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { player.seekBy(-10) }) { Text("−10", color = Color.White, fontSize = 22.sp) }
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
                    TextButton(onClick = { player.seekBy(10) }) { Text("+10", color = Color.White, fontSize = 22.sp) }
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
            TrackPanel(player, settings)
        }

        if (ended && next != null && !nextCancelled) {
            NextUp(
                next = next,
                onPlay = onNext,
                onCancel = { nextCancelled = true; controlsVisible = true },
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(32.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) Color.White else Color.White.copy(alpha = 0.14f),
        contentColor = if (active) Color.Black else Color.White,
    ) {
        Text(text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** Side panel: audio and subtitle tracks, subtitle delay. */
@Composable
private fun TrackPanel(player: MpvPlayer, settings: Settings) {
    val audio by player.audioTracks.collectAsState()
    val subtitles by player.subtitleTracks.collectAsState()
    val delay by player.subtitleDelay.collectAsState()
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

        PanelTitle("Décalage sous-titres")
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { player.shiftSubtitles(-0.1) }, enabled = subtitlesOn) { Text("−", color = Color.White) }
            Text(
                "%+.1f s".format(delay).replace('.', ',').replace("+0,0", "0,0"),
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.widthIn(min = 64.dp),
            )
            OutlinedButton(onClick = { player.shiftSubtitles(0.1) }, enabled = subtitlesOn) { Text("+", color = Color.White) }
            if (delay != 0.0) TextButton(onClick = { player.shiftSubtitles(-delay) }) { Text("Remettre à 0") }
        }
        PanelNote("Positif : les sous-titres arrivent plus tard.")

        Spacer(Modifier.size(16.dp))
        PanelNote(profileSummary(settings))
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

/** End of an episode: the next one starts after a short countdown, unless cancelled. */
@Composable
private fun NextUp(next: PlayItem, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var remaining by remember(next) { mutableIntStateOf(8) }
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
