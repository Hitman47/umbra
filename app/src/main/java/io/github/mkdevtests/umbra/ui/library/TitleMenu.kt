package io.github.mkdevtests.umbra.ui.library

import io.github.mkdevtests.umbra.ui.theme.focusRing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.history.seenOf
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons

/**
 * A title long-pressed somewhere: a film ([isShow] false, [key] its file), a
 * show (its key), one of its episodes ([episode] its file), or a saga ("saga:<id>").
 */
data class TitleTarget(val key: String, val isShow: Boolean = false, val episode: String? = null)

/** Where the menu's "Ouvrir la fiche" leads. */
class TitleLinks(
    val onOpenMovie: (String) -> Unit,
    val onOpenShow: (String) -> Unit,
    val onOpenSaga: (Int) -> Unit = {},
    val onOpenUniverse: (String) -> Unit = {},
)

/**
 * The long-press menu of a screen: call the returned function with the title
 * pressed, the menu opens over the screen.
 */
@Composable
fun rememberTitleMenu(viewModel: LibraryViewModel, links: TitleLinks): (TitleTarget) -> Unit {
    var target by remember { mutableStateOf<TitleTarget?>(null) }
    target?.let { pressed ->
        val library by viewModel.library.collectAsState()
        val history by viewModel.history.collectAsState()
        TitleMenu(pressed, library, history, viewModel, links) { target = null }
    }
    return { target = it }
}

@Composable
private fun TitleMenu(
    target: TitleTarget,
    library: Library,
    history: Map<String, Progress>,
    viewModel: LibraryViewModel,
    links: TitleLinks,
    onDismiss: () -> Unit,
) {
    target.key.removePrefix("saga:").toIntOrNull()?.takeIf { target.key.startsWith("saga:") }?.let { id ->
        val movies = library.movies.filter { it.sagaId == id }
        if (movies.isEmpty()) return
        val watched = movies.count { history[it.file]?.watched == true }
        MarkDialog(
            title = movies.first().saga ?: "Saga",
            caption = "$watched/${movies.size} films vus",
            watched = watched == movies.size,
            started = watched > 0,
            onOpen = { links.onOpenSaga(id) },
            onMark = { mark -> movies.forEach { viewModel.markWatched(it, mark) } },
            onDismiss = onDismiss,
        )
        return
    }
    if (target.isShow) {
        val show = library.shows.firstOrNull { it.key == target.key } ?: return
        val episode = target.episode?.let { file -> show.seasons.flatMap { it.episodes }.firstOrNull { it.file == file } }
        if (episode != null) {
            EpisodeMenu(show, episode, history, viewModel, onOpen = { links.onOpenShow(show.key) }, onDismiss = onDismiss)
            return
        }
        val seen = seenOf(show, history)
        MarkDialog(
            title = show.title,
            caption = if (seen.all) "Tout vu" else "${seen.watched}/${seen.total} épisodes vus",
            watched = seen.all,
            started = seen.watched > 0,
            onOpen = { links.onOpenShow(show.key) },
            onMark = { viewModel.markWatched(show, it) },
            onDismiss = onDismiss,
        )
        return
    }
    val movie = library.movies.firstOrNull { it.file == target.key } ?: return
    val progress = history[movie.file]
    MarkDialog(
        title = movie.title,
        caption = when {
            progress?.watched == true -> "Vu"
            progress?.inProgress == true -> "En cours"
            else -> "Pas encore vu"
        },
        watched = progress?.watched == true,
        started = progress?.inProgress == true,
        onOpen = { links.onOpenMovie(movie.file) },
        onMark = { viewModel.markWatched(movie, it) },
        onDismiss = onDismiss,
    )
}

/** An episode's menu: it alone, or every episode up to it (specials aside). [onOpen] null: on the show's page already. */
@Composable
fun EpisodeMenu(
    show: Show,
    episode: Episode,
    history: Map<String, Progress>,
    viewModel: LibraryViewModel,
    onOpen: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val progress = history[episode.file]
    val before = remember(show, episode) { episodesUpTo(show, episode) }
    MarkDialog(
        title = "${show.title} · S%02dE%02d".format(episode.season, episode.number),
        caption = episode.title ?: when {
            progress?.watched == true -> "Vu"
            progress?.inProgress == true -> "En cours"
            else -> "Pas encore vu"
        },
        watched = progress?.watched == true,
        started = progress?.inProgress == true,
        onOpen = onOpen,
        onMark = { viewModel.markWatched(show, it, listOf(episode)) },
        onDismiss = onDismiss,
    ) {
        val downloads by viewModel.downloads.collectAsState()
        if (downloads.none { it.key == episode.file }) {
            MenuRow(NyxaraIcons.Download, "Télécharger l'épisode") { onDismiss(); viewModel.download(show, listOf(episode)) }
            val following = episodesFrom(show, episode).take(DOWNLOAD_AHEAD)
            if (following.size > 1) {
                MenuRow(NyxaraIcons.Download, "Télécharger les ${following.size} prochains épisodes") { onDismiss(); viewModel.download(show, following) }
            }
        } else {
            MenuRow(NyxaraIcons.Close, "Supprimer de la tablette") { onDismiss(); viewModel.removeDownload(episode.file) }
        }
        if (before.size > 1 && before.any { history[it.file]?.watched != true }) {
            MenuRow(NyxaraIcons.Check, "Marquer vus jusqu'ici (${before.size} épisodes)") {
                onDismiss()
                viewModel.markWatched(show, true, before.filter { history[it.file]?.watched != true })
            }
        }
    }
}

/** Episodes downloaded at once from the menu: for a trip. */
private const val DOWNLOAD_AHEAD = 5

/** [episode] and the ones after it, in order (specials after a special only). */
fun episodesFrom(show: Show, episode: Episode): List<Episode> {
    val all = show.seasons.sortedBy { it.number }
        .filter { if (episode.season == 0) it.number == 0 else it.number > 0 }
        .flatMap { season -> season.episodes.sortedBy { it.number } }
    return all.drop(all.indexOfFirst { it.file == episode.file }.coerceAtLeast(0))
}

/** The episodes of [show] in order up to [episode]: its season's for a special. */
fun episodesUpTo(show: Show, episode: Episode): List<Episode> {
    val all = show.seasons.sortedBy { it.number }
        .filter { if (episode.season == 0) it.number == 0 else it.number > 0 }
        .flatMap { season -> season.episodes.sortedBy { it.number } }
    return all.take(all.indexOfFirst { it.file == episode.file } + 1)
}

/** The menu itself, for a title, a season or an episode. [onOpen] null: already on its page. */
@Composable
fun MarkDialog(
    title: String,
    caption: String,
    watched: Boolean,
    started: Boolean,
    onOpen: (() -> Unit)?,
    onMark: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                onOpen?.let { MenuRow(NyxaraIcons.Info, "Ouvrir la fiche") { onDismiss(); it() } }
                if (!watched) MenuRow(NyxaraIcons.Check, "Marquer comme vu") { onDismiss(); onMark(true) }
                if (watched || started) MenuRow(NyxaraIcons.Close, "Marquer comme non vu") { onDismiss(); onMark(false) }
                extra?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
