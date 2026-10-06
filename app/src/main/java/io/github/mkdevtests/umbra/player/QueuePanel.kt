package io.github.mkdevtests.umbra.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.mkdevtests.umbra.library.Tmdb
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.focusRing

private val Panel = Color(0xFF14121A)
private val Raised = Color(0xFF26223A)
private val Dim = Color(0xFFA6A1BA)
private val Accent = Color(0xFFA78BFA)

/**
 * The queue beside the picture: what plays, what comes next in the order it
 * will pass (a lazy list: 10,000+ videos scroll like ten), what played
 * before; Aléatoire, Répéter, Arrêter après. A touch plays a video now, a long
 * press offers the rest.
 */
@Composable
fun QueuePanel(queue: PlayerQueue, position: Double, duration: Double, onJump: (String, Boolean) -> Unit, onClose: () -> Unit) {
    val version = queue.version
    val upcoming = remember(version) { queue.upcoming() }
    val earlier = remember(version) { queue.earlier() }
    var showEarlier by remember { mutableStateOf(false) }
    val current = queue.current

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(440.dp)
            .background(Panel)
            // Taps inside the panel must not close it.
            .pointerInput(Unit) { detectTapGestures { } }
            .safeDrawingPadding()
            .padding(top = 12.dp),
    ) {
        Row(modifier = Modifier.padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("File d'attente", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(NyxaraIcons.Close, contentDescription = "Fermer", tint = Color.White) }
        }
        val noun = if (queue.perso) "vidéo" else "épisode"
        Text(
            "${queue.size} $noun${if (queue.size > 1) "s" else ""}" + (if (queue.shuffle) " · ordre aléatoire" else " · dans l'ordre") +
                if (!queue.order.complete) " · recherche des autres…" else "",
            color = Dim,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Chip("Aléatoire", queue.shuffle, queue::toggleShuffle)
            Chip("Répéter : ${queue.repeat.label}", queue.repeat != Repeat.None, queue::cycleRepeat)
            if (queue.shuffle) Chip("Mélanger à nouveau", false, queue::reshuffle)
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            item(key = "now-title") { Heading("EN COURS") }
            if (current != null) {
                item(key = "now") {
                    Box(modifier = Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Raised)) {
                        QueueRow(current, progress = if (duration > 0) (position / duration).toFloat() else null)
                    }
                }
            }
            item(key = "next-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Heading("À SUIVRE", Modifier.weight(1f))
                    if (upcoming.isNotEmpty()) Text("Toucher : lire maintenant", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(end = 24.dp, top = 12.dp))
                }
            }
            if (upcoming.isEmpty()) {
                item(key = "end") { Text("Fin de la file.", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
            }
            itemsIndexed(upcoming, key = { index, key -> "u$index:$key" }) { index, key ->
                val item = remember(key) { queue.item(key) }
                // A heading where the season changes ("Saison 3").
                val group = item.group
                val before = if (index == 0) current?.group else remember(upcoming, index) { queue.item(upcoming[index - 1]).group }
                Column {
                    if (group != null && group != before) Heading(group.uppercase())
                    MenuRow(item, onPlay = { onJump(key, false) }, onFromStart = { onJump(key, true) }, onNext = { queue.playNext(key) }, onRemove = { queue.remove(key) })
                }
            }
            if (earlier.isNotEmpty()) {
                item(key = "earlier-title") {
                    Text(
                        "Déjà lues (${earlier.size}) ${if (showEarlier) "⌄" else "›"}",
                        color = Dim,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().focusRing().clickable { showEarlier = !showEarlier }.padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
                if (showEarlier) {
                    itemsIndexed(earlier, key = { index, key -> "e$index:$key" }) { _, key ->
                        val item = remember(key) { queue.item(key) }
                        Box(modifier = Modifier.alpha(0.6f)) {
                            MenuRow(item, onPlay = { onJump(key, false) }, onFromStart = { onJump(key, true) }, onNext = { queue.playNext(key) }, onRemove = null)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color(0xFF2E2944))
        Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (queue.perso) "Arrêter après cette vidéo" else "Arrêter après celle-ci", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = queue.stopAfter, onCheckedChange = { queue.toggleStopAfter() })
        }
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) Color.White else Color.White.copy(alpha = 0.14f),
        contentColor = if (active) Color.Black else Color.White,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** A video of the list; a long press opens what can be done with it. */
@Composable
private fun MenuRow(item: PlayItem, onPlay: () -> Unit, onFromStart: () -> Unit, onNext: () -> Unit, onRemove: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.focusRing().combinedClickable(onLongClick = { menu = true }, onClick = onPlay)) {
        QueueRow(item, progress = null)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Lire maintenant") }, onClick = { menu = false; onPlay() })
            DropdownMenuItem(text = { Text("Lire juste après") }, onClick = { menu = false; onNext() })
            DropdownMenuItem(text = { Text("Reprendre au début") }, onClick = { menu = false; onFromStart() })
            if (onRemove != null) DropdownMenuItem(text = { Text("Retirer de la file") }, onClick = { menu = false; onRemove() })
        }
    }
}

@Composable
private fun QueueRow(item: PlayItem, progress: Float?) {
    // An episode: "S02E05 · Titre" under the show's name; a Perso video: its name, its folder.
    val title = if (item.group != null) item.subtitle ?: item.title else item.title
    val detail = listOfNotNull(item.subtitle.takeIf { item.group == null }, item.minutes?.let { "$it min" }).joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A3450)),
            contentAlignment = Alignment.Center,
        ) {
            if (item.image != null) {
                AsyncImage(model = Tmdb.image(item.image, "w300"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(54.dp))
            } else {
                Icon(NyxaraIcons.Play, contentDescription = null, tint = Accent.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.18f),
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}
