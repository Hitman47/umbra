package io.github.mkdevtests.umbra.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.mkdevtests.umbra.ui.theme.focusRing
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.player.PlaybackLog
import io.github.mkdevtests.umbra.player.summarize
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle

/**
 * The playbacks measured: medians per NAS, network and protocol first, then
 * each playback. "Copier" puts it all on the clipboard, to paste in a message.
 */
@Composable
fun MeasuresScreen(log: PlaybackLog, onBack: () -> Unit) {
    val context = LocalContext.current
    val measures by log.measures.collectAsState()
    val summaries = remember(measures) { summarize(measures) }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle("Mesures de lecture", onBack) {
            TextButton(onClick = {
                val text = (listOf("Résumé :") + summaries.map { it.line() } + listOf("", "Lectures :") + measures.map { it.line() }).joinToString("\n")
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Mesures Nyxara", text))
                Toast.makeText(context, "Mesures copiées", Toast.LENGTH_SHORT).show()
            }, enabled = measures.isNotEmpty()) { Text("Copier") }
            TextButton(modifier = Modifier.focusRing(RoundedCornerShape(50)), onClick = log::clear, enabled = measures.isNotEmpty()) { Text("Effacer") }
        }
        if (measures.isEmpty()) {
            Text(
                "Chaque lecture est mesurée : temps d'ouverture, durée des sauts, coupures, débit du NAS. Lance quelques vidéos, puis reviens ici.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Heading("Médianes par configuration") }
            items(summaries) { Text(it.line(), style = MaterialTheme.typography.bodyMedium) }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { Heading("Lectures") }
            items(measures) { Text(it.line(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
