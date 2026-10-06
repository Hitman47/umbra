package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.library.RemoteTitle
import io.github.mkdevtests.umbra.nas.toUserMessage
import io.github.mkdevtests.umbra.requests.Release
import io.github.mkdevtests.umbra.requests.searchQuery
import io.github.mkdevtests.umbra.ui.theme.GlassIconButton
import io.github.mkdevtests.umbra.ui.theme.GlowButton
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.Tag
import io.github.mkdevtests.umbra.ui.theme.focusRing
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * A title the library doesn't have (a similar title): its TMDB page, without
 * a play button; searched with Prowlarr, the release chosen sent to qBittorrent.
 */
@Composable
fun RemoteScreen(tmdbId: Int, isShow: Boolean, viewModel: LibraryViewModel, links: TitleLinks, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NyxaraApp
    val library by viewModel.library.collectAsState()
    val requests by app.requests.requested.collectAsState()
    val settings by app.requests.settings.collectAsState()
    var title by remember(tmdbId) { mutableStateOf<RemoteTitle?>(null) }
    var loaded by remember(tmdbId) { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(tmdbId, isShow) {
        title = viewModel.remote(tmdbId, isShow)
        loaded = true
    }
    // Arrived meanwhile: its own page.
    val owned = if (isShow) library.shows.firstOrNull { it.tmdbId == tmdbId }?.key else library.movies.firstOrNull { it.tmdbId == tmdbId }?.file
    val asked = requests.firstOrNull { it.tmdbId == tmdbId && it.isShow == isShow }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box {
                Backdrop(title?.backdrop)
                GlassIconButton(NyxaraIcons.Back, "Retour", onBack, modifier = Modifier.safeDrawingPadding().padding(12.dp))
            }
        }
        val shown = title
        if (shown == null) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (loaded) Text("Page introuvable (hors connexion ?)", color = MaterialTheme.colorScheme.onSurfaceVariant) else CircularProgressIndicator()
                }
            }
            return@LazyColumn
        }
        item {
            Row(modifier = Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Poster(shown.poster, shown.title, modifier = Modifier.width(120.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(shown.title, style = MaterialTheme.typography.headlineSmall)
                    shown.originalTitle?.takeIf { it != shown.title }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        listOfNotNull(
                            shown.year?.toString(),
                            formatRuntime(shown.runtime),
                            shown.seasons?.let { "$it saison${if (it > 1) "s" else ""}" },
                            formatRating(shown.rating),
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(if (owned != null) "Dans la bibliothèque" else "Absent de la bibliothèque")
                        shown.genres.take(3).forEach { Tag(it) }
                    }
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    owned != null -> GlowButton("Ouvrir sa page", onClick = { if (isShow) links.onOpenShow(owned) else links.onOpenMovie(owned) })
                    settings.canSearch -> GlowButton("Rechercher", onClick = { searching = true }, icon = NyxaraIcons.Search)
                    else -> Text("Pour chercher ce titre : Réglages › Recherche externe (Prowlarr, qBittorrent).", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                asked?.let {
                    Text(
                        "Demandé le ${DateFormat.getDateInstance(DateFormat.SHORT, Locale.FRANCE).format(Date(it.at))} · ${it.release}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                shown.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (shown.directors.isNotEmpty()) Text((if (isShow) "Création : " else "Réalisation : ") + shown.directors.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                if (shown.cast.isNotEmpty()) Text("Avec " + shown.cast.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    title?.takeIf { searching }?.let { shown -> SearchDialog(app, shown, onClose = { searching = false }) }
}

/** Prowlarr's releases for a title, the preferred quality first; one chosen goes to qBittorrent after a confirmation. */
@Composable
private fun SearchDialog(app: NyxaraApp, title: RemoteTitle, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(searchQuery(title.title, title.year, title.isShow)) }
    var results by remember { mutableStateOf<List<Release>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<Release?>(null) }
    fun search() {
        busy = true
        message = null
        scope.launch {
            runCatching { app.requests.search(query, title.isShow) }
                .onSuccess { results = it; if (it.isEmpty()) message = "Aucun résultat." }
                .onFailure { message = "Recherche impossible : ${it.toUserMessage()}" }
            busy = false
        }
    }
    LaunchedEffect(Unit) { search() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Rechercher « ${title.title} »", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::search, enabled = !busy && query.isNotBlank()) { Text("Chercher") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn {
                            items(results.orEmpty(), key = { it.link }) { release ->
                                Column(modifier = Modifier.fillMaxWidth().focusRing().clickable { chosen = release }.padding(vertical = 8.dp)) {
                                    Text(release.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(release.quality, sizeLabel(release.size), release.seeders?.let { "$it sources" }, release.indexer, release.published)
                                            .joinToString("  ·  "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer") } },
    )
    chosen?.let { release ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text("Envoyer à qBittorrent ?") },
            text = { Text(release.title) },
            confirmButton = {
                TextButton(onClick = {
                    chosen = null
                    busy = true
                    scope.launch {
                        message = runCatching { app.requests.send(release, title.tmdbId, title.isShow, title.title) }
                            .fold({ "Envoyé à qBittorrent." }, { "Envoi impossible : ${it.toUserMessage()}" })
                        busy = false
                    }
                }, enabled = app.requests.settings.value.canSend) { Text("Envoyer") }
            },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("Annuler") } },
        )
    }
}

private fun sizeLabel(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1L shl 30).toDouble())
    else -> String.format(Locale.FRANCE, "%d Mo", bytes / (1L shl 20))
}
