package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.nas.NasCheck
import io.github.mkdevtests.umbra.nas.NasState
import io.github.mkdevtests.umbra.ui.theme.focusRing
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The keys of the titles that can't be read now (their NAS doesn't answer): their posters are dimmed. */
val LocalUnavailable = staticCompositionLocalOf<Set<String>> { emptySet() }

val OnlineColor = Color(0xFF4ADE80)
val OfflineColor = Color(0xFFF87171)

@Composable
private fun Dot(online: Boolean, size: Int = 8) {
    Box(modifier = Modifier.size(size.dp).background(if (online) OnlineColor else OfflineColor, CircleShape))
}

/**
 * Whether each NAS answers, small, at the top right: "● Zima en ligne ● Zima 2
 * hors ligne" on a tablet; on a phone the dots, with the names of those that
 * don't answer. Touched: the details and "Masquer ce qui est indisponible".
 */
@Composable
fun NasStatusChip(
    states: List<NasState>,
    wide: Boolean,
    hide: Boolean,
    titles: Map<String, Int>,
    history: List<NasCheck>,
    onHide: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    if (states.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val anyOffline = states.any { !it.online }
    Box {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .focusRing(RoundedCornerShape(16.dp))
                .border(1.dp, if (anyOffline) OfflineColor.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .background(if (anyOffline) OfflineColor.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(16.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 5.dp),
        ) {
            if (wide) {
                states.forEach { state ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(state.online)
                        Text(state.label, style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (state.online) "en ligne" else "hors ligne",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.online) OnlineColor else OfflineColor,
                        )
                    }
                }
            } else {
                states.forEach { Dot(it.online) }
                states.filter { !it.online }.takeIf { it.isNotEmpty() }?.let { off ->
                    Text(off.joinToString(", ") { it.label }, style = MaterialTheme.typography.labelSmall, color = OfflineColor, maxLines = 1)
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(modifier = Modifier.width(340.dp).padding(vertical = 4.dp)) {
                Text("Sources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                states.forEach { state ->
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Dot(state.online, 10)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.label, style = MaterialTheme.typography.bodyLarge)
                            val count = titles[state.id]?.let { " · $it titre${if (it > 1) "s" else ""}" + if (state.online) "" else " gardés" }.orEmpty()
                            Text(
                                (if (state.online) "En ligne" else "Hors ligne depuis ${DateFormat.getTimeInstance(DateFormat.SHORT, Locale.FRANCE).format(Date(state.since))}") + count,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!state.online) TextButton(modifier = Modifier.focusRing(RoundedCornerShape(50)), onClick = onRetry) { Text("Réessayer") }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier.focusRing().clickable { onHide(!hide) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Masquer ce qui est indisponible", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Les titres d'un NAS hors ligne quittent les listes, sauf ceux téléchargés sur l'appareil. Ils reviennent dès qu'il répond.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(modifier = Modifier.focusRing(RoundedCornerShape(50)), checked = hide, onCheckedChange = onHide)
                }
                var showHistory by remember { mutableStateOf(false) }
                TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showHistory) "Masquer l'historique" else "Dernières vérifications ›")
                }
                if (showHistory) {
                    val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
                    Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (history.isEmpty()) Text("Aucune pour l'instant.", style = MaterialTheme.typography.bodySmall)
                        history.forEach { check ->
                            Text(
                                "${time.format(Date(check.at))} · ${check.reason.label}\n" + check.results.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Vérifié toutes les minutes tant que l'appli est ouverte, et après chaque changement de réseau. Hors ligne seulement après deux échecs de suite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** On a dimmed poster: its NAS doesn't answer. */
@Composable
internal fun UnavailableMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(6.dp).size(22.dp).background(MaterialTheme.colorScheme.background, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Dot(online = false) }
}
