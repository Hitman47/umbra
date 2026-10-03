package io.github.mkdevtests.umbra.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.ui.library.Poster
import io.github.mkdevtests.umbra.ui.library.PosterShape
import java.util.Locale

/** What the library knows about the NAS paths, to show posters while browsing. */
private class PathIndex(library: Library) {
    val showByFolder: Map<String, Show> = library.shows.flatMap { show -> show.folders.map { it to show } }.toMap()
    val movieByFolder: Map<String, Movie> = library.movies.mapNotNull { movie -> movie.folder?.let { it to movie } }.toMap()
    val movieByFile: Map<String, Movie> = library.movies.flatMap { movie -> (listOf(movie.file) + movie.copies.map { it.file }).map { it to movie } }.toMap()
    val episodeByFile: Map<String, Episode> = library.shows.flatMap { show ->
        show.seasons.flatMap { season -> season.episodes.map { it.file to it } }
    }.toMap()
}

/**
 * The NAS shares as they are, Infuse style: a show's or a film's folder shows
 * its poster and opens its page (a long press opens the folder itself), other
 * folders and videos show as tiles.
 */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    library: Library,
    onOpenMovie: (String) -> Unit,
    onOpenShow: (String) -> Unit,
    /** A folder was just left out: its titles leave the library. */
    onExcluded: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val index = remember(library) { PathIndex(library) }
    var excluding by remember { mutableStateOf<String?>(null) }
    // The folder long-pressed: open it, or leave it out of the library.
    var menu by remember { mutableStateOf<NasEntry?>(null) }

    BackHandler(enabled = state.path.isNotEmpty()) { viewModel.up() }

    menu?.let { entry ->
        // A share itself is followed or not from its source; only the folders inside one can be left out here.
        val canExclude = '\\' in entry.path
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(entry.name) },
            text = {
                Column {
                    TextButton(onClick = { menu = null; viewModel.open(entry.path) }) { Text("Ouvrir le dossier") }
                    if (canExclude) {
                        TextButton(onClick = { menu = null; excluding = entry.path }) {
                            Text("Exclure de la bibliothèque", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menu = null }) { Text("Fermer") } },
        )
    }

    excluding?.let { path ->
        AlertDialog(
            onDismissRequest = { excluding = null },
            title = { Text("Exclure « ${path.substringAfterLast('\\')} » ?") },
            text = {
                Text("Ce dossier n'est plus analysé ni affiché, et ses vidéos ne sont plus lisibles. Pour le rétablir : Réglages › Sources.")
            },
            confirmButton = {
                TextButton(onClick = {
                    excluding = null
                    viewModel.exclude(path)
                    onExcluded()
                }) { Text("Exclure") }
            },
            dismissButton = { TextButton(onClick = { excluding = null }) { Text("Annuler") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.path.isNotEmpty()) TextButton(onClick = { viewModel.up() }) { Text("←") }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = state.crumbs.lastOrNull().orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.crumbs.joinToString(" › "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A folder inside a share; a whole share is left out by unticking it in the source.
            if ('\\' in state.path) TextButton(onClick = { excluding = state.path }) { Text("Exclure") }
        }
        HorizontalDivider()

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.error != null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::refresh) { Text("Réessayer") }
                }
                state.entries.isEmpty() -> Text(
                    "Aucune vidéo dans ce dossier.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        val open = {
                            if (entry.isDirectory) viewModel.open(entry.path)
                            else context.startActivity(viewModel.playIntent(entry))
                        }
                        val show = if (entry.isDirectory) index.showByFolder[entry.path] else null
                        val movie = if (entry.isDirectory) index.movieByFolder[entry.path] else index.movieByFile[entry.path]
                        val longPress = { menu = entry }
                        when {
                            show != null -> Tile(show.title, show.year?.toString(), onClick = { onOpenShow(show.key) }, onLongClick = longPress) {
                                Poster(show.poster, show.title, modifier = Modifier.fillMaxWidth())
                            }
                            movie != null && entry.isDirectory -> Tile(movie.title, movie.year?.toString(), onClick = { onOpenMovie(movie.file) }, onLongClick = longPress) {
                                Poster(movie.poster, movie.title, modifier = Modifier.fillMaxWidth())
                            }
                            movie != null -> Tile(movie.title, movie.year?.toString(), onClick = { onOpenMovie(movie.file) }, onLongClick = open) {
                                Poster(movie.poster, movie.title, modifier = Modifier.fillMaxWidth())
                            }
                            entry.isDirectory -> Tile(entry.name, null, onClick = open, onLongClick = longPress) { GlyphCard { folder(it) } }
                            else -> {
                                val episode = index.episodeByFile[entry.path]
                                val code = episode?.let { "S%02dE%02d".format(it.season, it.number) }
                                Tile(episode?.title ?: entry.name, listOfNotNull(code, formatSize(entry.size)).joinToString(" · "), onClick = open) {
                                    GlyphCard { play(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, caption: String?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, artwork: @Composable () -> Unit) {
    Column(modifier = Modifier.combinedClickable(onLongClick = onLongClick, onClick = onClick)) {
        artwork()
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        caption?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Poster-sized card with a drawn glyph, for folders and videos the library doesn't know. */
@Composable
private fun GlyphCard(glyph: DrawScope.(Color) -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(PosterShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(52.dp)) { glyph(color) }
    }
}

private fun DrawScope.folder(color: Color) {
    val w = size.width
    val h = size.height
    val shape = Path().apply {
        moveTo(0f, h * 0.18f)
        lineTo(w * 0.38f, h * 0.18f)
        lineTo(w * 0.48f, h * 0.3f)
        lineTo(w, h * 0.3f)
        lineTo(w, h * 0.86f)
        lineTo(0f, h * 0.86f)
        close()
    }
    drawPath(shape, color)
}

private fun DrawScope.play(color: Color) {
    val w = size.width
    val h = size.height
    val shape = Path().apply {
        moveTo(w * 0.28f, h * 0.15f)
        lineTo(w * 0.85f, h * 0.5f)
        lineTo(w * 0.28f, h * 0.85f)
        close()
    }
    drawPath(shape, color)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1L shl 30).toDouble())
    else -> String.format(Locale.FRANCE, "%d Mo", bytes / (1L shl 20))
}
