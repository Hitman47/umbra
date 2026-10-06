package io.github.mkdevtests.umbra.ui.library

import io.github.mkdevtests.umbra.ui.theme.remoteFriendly
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.library.RemoteTitle
import io.github.mkdevtests.umbra.nas.toUserMessage
import io.github.mkdevtests.umbra.requests.Release
import io.github.mkdevtests.umbra.requests.ReleaseFilter
import io.github.mkdevtests.umbra.requests.ReleaseSort
import io.github.mkdevtests.umbra.requests.ageLabel
import io.github.mkdevtests.umbra.requests.categoryFor
import io.github.mkdevtests.umbra.requests.grouped
import io.github.mkdevtests.umbra.requests.languagesOf
import io.github.mkdevtests.umbra.requests.qualitiesOf
import io.github.mkdevtests.umbra.requests.ranked
import io.github.mkdevtests.umbra.requests.searchQuery
import io.github.mkdevtests.umbra.ui.theme.GlassIconButton
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.Tag
import io.github.mkdevtests.umbra.ui.theme.focusRing
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Prowlarr's releases for a title, full screen: sorted (by default 1080p, then
 * MULTI / VFF…, then the most seeded; the others follow), narrowed by chips,
 * the same torrent on several indexers on one line. One chosen is sent after
 * a look at its details.
 */
@Composable
fun ReleaseSearch(app: NyxaraApp, title: RemoteTitle, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(searchQuery(title.title, title.year, title.isShow)) }
    var results by remember { mutableStateOf<List<Release>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(ReleaseSort.Best) }
    var reversed by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ReleaseFilter()) }
    var chosen by remember { mutableStateOf<Pair<Release, List<Release>>?>(null) }
    fun search() {
        busy = true
        message = null
        scope.launch {
            runCatching { app.requests.search(query, title.isShow) }
                .onSuccess { results = it; filter = ReleaseFilter(); if (it.isEmpty()) message = "Aucun résultat." }
                .onFailure { message = "Recherche impossible : ${it.toUserMessage()}" }
            busy = false
        }
    }
    LaunchedEffect(Unit) { search() }

    val all = results.orEmpty()
    val lines = remember(all, sort, reversed, filter) { grouped(ranked(filter.apply(all), sort, reversed = reversed)) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(NyxaraIcons.Back, "Fermer", onClose)
                Text(
                    "Rechercher « ${title.title} »",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.weight(1f).remoteFriendly(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (!busy && query.isNotBlank()) search() }),
                )
                TextButton(onClick = ::search, enabled = !busy && query.isNotBlank()) { Text("Chercher") }
            }
            if (all.isNotEmpty()) {
                Chips {
                    // The chosen sort touched again: the other way round.
                    ReleaseSort.entries.forEach { s ->
                        FilterChip(
                            selected = sort == s,
                            onClick = { if (sort == s) reversed = !reversed else { sort = s; reversed = false } },
                            label = { Text(s.label + if (sort == s) (if (reversed) " ↑" else " ↓") else "") },
                        )
                    }
                }
                Chips {
                    FilterChip(selected = filter.withSeeders, onClick = { filter = filter.copy(withSeeders = !filter.withSeeders) }, label = { Text("Avec sources") })
                    qualitiesOf(all).forEach { q ->
                        FilterChip(selected = filter.quality == q, onClick = { filter = filter.copy(quality = q.takeIf { it != filter.quality }) }, label = { Text(q) })
                    }
                    languagesOf(all).forEach { l ->
                        FilterChip(selected = filter.language == l, onClick = { filter = filter.copy(language = l.takeIf { it != filter.language }) }, label = { Text(l) })
                    }
                    if (title.isShow) {
                        all.mapNotNull { it.parsed.season }.distinct().sorted().forEach { s ->
                            FilterChip(selected = filter.season == s, onClick = { filter = filter.copy(season = s.takeIf { it != filter.season }) }, label = { Text("Saison $s") })
                        }
                    }
                    all.mapNotNull { it.indexer }.distinct().sorted().forEach { i ->
                        FilterChip(selected = filter.indexer == i, onClick = { filter = filter.copy(indexer = i.takeIf { it != filter.indexer }) }, label = { Text(i) })
                    }
                }
                Text(
                    "${lines.size} version${if (lines.size > 1) "s" else ""}" + if (lines.size < all.size) " (sur ${all.size} résultats)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(lines, key = { it.first.dedupKey + "|" + it.first.guid }) { line ->
                            ReleaseLine(line.first, line.second, title, onClick = { chosen = line })
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
        chosen?.let { (release, others) ->
            ReleaseDetails(release, others, title.isShow, canSend = app.requests.settings.value.canSend, onDismiss = { chosen = null }) {
                chosen = null
                busy = true
                scope.launch {
                    val target = if (app.requests.settings.value.canSend && release.protocol == "torrent") "qBittorrent" else "Prowlarr"
                    message = runCatching { app.requests.send(release, title.tmdbId, title.isShow, title.title) }
                        .fold({ "Envoyé à $target." }, { "Envoi impossible : ${it.toUserMessage()}" })
                    busy = false
                }
            }
        }
    }
}

@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun ReleaseLine(release: Release, others: List<Release>, title: RemoteTitle, onClick: () -> Unit) {
    val parsed = release.parsed
    Column(
        modifier = Modifier.fillMaxWidth().focusRing().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(parsed.heading, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (parsed.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                parsed.tags.forEach { Tag(it.label) }
            }
        }
        Text(
            metaLine(release, others),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        mismatch(release, title)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** "4,2 Go · 120 ↑ 3 ↓ · 5 j · YggTorrent +2". */
private fun metaLine(release: Release, others: List<Release>) = listOfNotNull(
    sizeLabel(release.size),
    release.seeders?.let { s -> "$s ↑" + (release.leechers?.let { " $it ↓" } ?: "") },
    ageLabel(release.ageHours) ?: release.published,
    release.indexer?.let { it + if (others.isNotEmpty()) " +${others.size}" else "" },
    release.protocol.takeIf { it != "torrent" },
).joinToString("  ·  ")

/** Another year than the title's (a remake?): said, not hidden. */
private fun mismatch(release: Release, title: RemoteTitle): String? {
    val year = release.parsed.year ?: return null
    val expected = title.year ?: return null
    return if (!title.isShow && abs(year - expected) > 1) "Année $year (le titre : $expected)" else null
}

@Composable
private fun ReleaseDetails(release: Release, others: List<Release>, isShow: Boolean, canSend: Boolean, onDismiss: () -> Unit, onSend: () -> Unit) {
    val context = LocalContext.current
    val viaQbit = canSend && release.protocol == "torrent"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(release.parsed.heading) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(release.title, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (release.parsed.tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        release.parsed.tags.forEach { Tag(it.label) }
                    }
                }
                Text(metaLine(release, emptyList()), style = MaterialTheme.typography.bodyMedium)
                if (others.isNotEmpty()) {
                    Text("Aussi sur : " + others.mapNotNull { it.indexer }.distinct().joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                release.infoUrl?.let { url ->
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("Page sur l'indexeur") }
                }
                Text(
                    if (viaQbit) "Envoyé à qBittorrent, catégorie « ${categoryFor(isShow)} »." else "Envoyé par Prowlarr à son client de téléchargement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Envoyer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

private fun sizeLabel(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1L shl 30).toDouble())
    else -> String.format(Locale.FRANCE, "%d Mo", bytes / (1L shl 20))
}
