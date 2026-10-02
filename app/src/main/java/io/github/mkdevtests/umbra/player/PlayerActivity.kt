package io.github.mkdevtests.umbra.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.mkdevtests.umbra.ui.theme.UmbraTheme
import kotlinx.coroutines.delay

/** Full-screen player. Receives the media location in [EXTRA_URL]. */
class PlayerActivity : ComponentActivity() {

    private lateinit var player: MpvPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        player = MpvPlayer(this)
        player.play(toMpvPath(url))

        setContent {
            UmbraTheme {
                PlayerScreen(player = player, title = intent.getStringExtra(EXTRA_TITLE) ?: url, onBack = ::finish)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
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
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, url: String, title: String? = null): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }
}

@Composable
private fun PlayerScreen(player: MpvPlayer, title: String, onBack: () -> Unit) {
    val position by player.position.collectAsState()
    val duration by player.duration.collectAsState()
    val paused by player.paused.collectAsState()
    val buffering by player.buffering.collectAsState()
    val audio by player.audioLabel.collectAsState()
    val subtitles by player.subtitleLabel.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    // While dragging, the slider shows the finger position, not mpv's.
    var dragPosition by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(controlsVisible, paused) {
        if (controlsVisible && !paused) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controlsVisible = !controlsVisible
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
                    TextButton(onClick = onBack) { Text("← Retour", color = Color.White) }
                    Text(title, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showInfo = !showInfo }) { Text("Infos", color = Color.White) }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { player.seekBy(-10) }) { Text("−10 s", color = Color.White, fontSize = 20.sp) }
                    TextButton(onClick = { player.togglePause() }) {
                        Text(if (paused) "▶" else "❚❚", color = Color.White, fontSize = 40.sp)
                    }
                    TextButton(onClick = { player.seekBy(10) }) { Text("+10 s", color = Color.White, fontSize = 20.sp) }
                }

                Column(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp)) {
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
                        Text(formatTime(duration), color = Color.White, fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { player.cycleAudio() }) { Text("Audio : $audio", color = Color.White, maxLines = 1) }
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = { player.cycleSubtitles() }) { Text("Sous-titres : $subtitles", color = Color.White, maxLines = 1) }
                    }
                }
            }
        }
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
