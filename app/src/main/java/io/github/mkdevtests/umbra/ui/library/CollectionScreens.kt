package io.github.mkdevtests.umbra.ui.library

import io.github.mkdevtests.umbra.ui.theme.LocalCardScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.browse.naturalCompare
import io.github.mkdevtests.umbra.history.seenOf
import io.github.mkdevtests.umbra.history.without
import io.github.mkdevtests.umbra.library.Related
import io.github.mkdevtests.umbra.library.SagaPage
import io.github.mkdevtests.umbra.library.inRoot
import io.github.mkdevtests.umbra.library.universesOf
import io.github.mkdevtests.umbra.ui.theme.GlassButton
import io.github.mkdevtests.umbra.ui.theme.GlassIconButton
import io.github.mkdevtests.umbra.ui.theme.GlowButton
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle

/** A home shortcut: the films and shows of one folder of the NAS ("Anime"), with the grid's filters. */
@Composable
fun ShortcutScreen(root: String, viewModel: LibraryViewModel, links: TitleLinks, onBack: () -> Unit) {
    val full by viewModel.library.collectAsState()
    val hidden by viewModel.hidden.collectAsState()
    val history by viewModel.history.collectAsState()
    val library = remember(full, hidden, root) { full.without(hidden).inRoot(root) }
    val menu = rememberTitleMenu(viewModel, links)
    // Films, shows or both: only offered when the folder holds both.
    var only by rememberSaveable(root) { mutableStateOf<Boolean?>(null) }
    val items = remember(library, history, only) {
        val movies = if (only != true) library.movies.map { movieItem(it, history) } else emptyList()
        val shows = if (only != false) showItems(library.shows, history) else emptyList()
        (movies + shows).sortedWith { a, b -> naturalCompare(a.title, b.title) }
    }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle(root, onBack)
        PosterGrid(
            items = items,
            emptyText = "Aucun titre dans ce dossier.",
            onClick = { key -> if (items.first { it.key == key }.isShow) links.onOpenShow(key) else links.onOpenMovie(key) },
            noun = "titre",
            onLongClick = { menu(TitleTarget(it.key, it.isShow)) },
            chips = {
                if (library.movies.isNotEmpty() && library.shows.isNotEmpty()) {
                    FilterChip(selected = only == false, onClick = { only = if (only == false) null else false }, label = { Text("Films") })
                    FilterChip(selected = only == true, onClick = { only = if (only == true) null else true }, label = { Text("Séries") })
                }
            },
        )
    }
}

/**
 * A saga: TMDB's description and all its films in release order, those
 * missing from the library dimmed; plays the first one not watched.
 */
@Composable
fun SagaScreen(id: Int, viewModel: LibraryViewModel, links: TitleLinks, onBack: () -> Unit) {
    val context = LocalContext.current
    val library by viewModel.library.collectAsState()
    val history by viewModel.history.collectAsState()
    val owned = remember(library, id) { library.movies.filter { it.sagaId == id }.sortedBy { it.year ?: Int.MAX_VALUE } }
    val byFile = remember(owned) { owned.associateBy { it.file } }
    var page by remember(id) { mutableStateOf<SagaPage?>(null) }
    LaunchedEffect(id) { page = viewModel.saga(id) }
    val menu = rememberTitleMenu(viewModel, links)
    val parts = page?.parts ?: owned.map { Related(it.title, it.year, it.poster, movie = it.file) }
    val next = owned.firstOrNull { history[it.file]?.watched != true } ?: owned.firstOrNull()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp * LocalCardScale.current),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box {
                Backdrop(page?.backdrop ?: owned.firstNotNullOfOrNull { it.backdrop })
                GlassIconButton(NyxaraIcons.Back, "Retour", onBack, modifier = Modifier.safeDrawingPadding().padding(12.dp))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(page?.name?.ifEmpty { null } ?: owned.firstNotNullOfOrNull { it.saga } ?: "Saga", style = MaterialTheme.typography.headlineMedium)
                val count = if (parts.size > owned.size) "${owned.size} films sur ${parts.size} dans la bibliothèque" else "${owned.size} films"
                val watched = owned.count { history[it.file]?.watched == true }
                Text(
                    listOfNotNull(count, "$watched vus".takeIf { watched > 0 }).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                page?.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (next != null) {
                        val resume = history[next.file]?.inProgress == true
                        GlowButton("${if (resume) "Reprendre" else "Lecture"} · ${next.title}", onClick = { context.startActivity(viewModel.playIntent(next)) })
                    }
                    if (owned.isNotEmpty()) GlassButton("Vu / non vu", onClick = { menu(TitleTarget("saga:$id")) }, icon = NyxaraIcons.Check)
                }
            }
        }
        items(parts) { part ->
            val movie = part.movie?.let(byFile::get)
            Box(modifier = Modifier.padding(horizontal = 10.dp).alpha(if (movie != null) 1f else 0.45f)) {
                PosterCard(
                    PosterItem(
                        part.movie ?: part.title, part.title, part.year, part.poster,
                        subtitle = "absent".takeIf { movie == null },
                        badge = movie?.let { seenOf(it, history).badge },
                    ),
                    onClick = { movie?.let { links.onOpenMovie(it.file) } },
                    onLongClick = movie?.let { { menu(TitleTarget(it.file)) } },
                )
            }
        }
    }
}

/**
 * A universe: the library's titles tied by their characters or a franchise
 * ("Batman": the Burton films, Nolan's trilogy, The Batman, Joker…), in release order.
 */
@Composable
fun UniverseScreen(name: String, viewModel: LibraryViewModel, links: TitleLinks, onBack: () -> Unit) {
    val full by viewModel.library.collectAsState()
    val hidden by viewModel.hidden.collectAsState()
    val history by viewModel.history.collectAsState()
    val library = remember(full, hidden) { full.without(hidden) }
    val universe = remember(library, name) { universesOf(library).firstOrNull { it.name == name } }
    val menu = rememberTitleMenu(viewModel, links)
    val items = remember(universe, library, history) {
        val byFile = library.movies.associateBy { it.file }
        val byKey = library.shows.associateBy { it.key }
        universe?.titles.orEmpty().mapNotNull { title ->
            title.movie?.let(byFile::get)?.let { movieItem(it, history) } ?: title.show?.let(byKey::get)?.let { showItems(listOf(it), history).single() }
        }
    }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle(name, onBack)
        PosterGrid(
            items = items,
            emptyText = "Cet univers n'est plus dans la bibliothèque.",
            onClick = { key -> if (items.first { it.key == key }.isShow) links.onOpenShow(key) else links.onOpenMovie(key) },
            noun = "titre",
            onLongClick = { menu(TitleTarget(it.key, it.isShow)) },
        )
    }
}
