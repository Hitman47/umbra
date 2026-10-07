package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.library.RemoteTitle
import io.github.mkdevtests.umbra.ui.theme.GlassIconButton
import io.github.mkdevtests.umbra.ui.theme.GlowButton
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.Tag
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
                    // A child's profile asks for nothing.
                    app.profile.child -> Unit
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
    title?.takeIf { searching }?.let { shown -> ReleaseSearch(app, shown, onClose = { searching = false }) }
}
