package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.mkdevtests.umbra.history.without
import io.github.mkdevtests.umbra.home.continueWatching
import io.github.mkdevtests.umbra.home.newest
import io.github.mkdevtests.umbra.library.sagasOf
import io.github.mkdevtests.umbra.ui.theme.ScreenTitle

/**
 * A row of the home screen in full, as a grid with the usual filters and
 * orders: everything under way, every new arrival, every film or show not
 * started, every saga, every title of a genre. The grid loads as it scrolls.
 */
@Composable
fun ShelfScreen(id: String, viewModel: LibraryViewModel, links: TitleLinks, onBack: () -> Unit) {
    val full by viewModel.library.collectAsState()
    val hidden by viewModel.hidden.collectAsState()
    val history by viewModel.history.collectAsState()
    val library = remember(full, hidden) { full.without(hidden) }
    val menu = rememberTitleMenu(viewModel, links)
    val movieByFile = remember(library) { library.movies.associateBy { it.file } }
    val showByKey = remember(library) { library.shows.associateBy { it.key } }
    val (title, items) = remember(id, library, history) {
        when {
            id == "resume" -> "Lecture en cours" to continueWatching(library, history).mapNotNull { item ->
                item.movie?.let { movieItem(it, history) } ?: item.show?.let { showItems(listOf(it), history).single() }
            }
            id == "fresh" -> "Ajouts récents" to newest(library, count = Int.MAX_VALUE).mapNotNull { pick ->
                if (pick.isShow) showByKey[pick.key]?.let { showItems(listOf(it), history).single() } else movieByFile[pick.key]?.let { movieItem(it, history) }
            }
            id == "movies" -> "Films à découvrir" to library.movies.filter { it.file !in history }
                .sortedByDescending { it.rating ?: 0.0 }.map { movieItem(it, history) }
            id == "shows" -> "Séries à découvrir" to showItems(
                library.shows.filter { show -> show.seasons.none { season -> season.episodes.any { it.file in history } } }.sortedByDescending { it.rating ?: 0.0 },
                history,
            )
            id == "sagas" -> "Sagas" to sagasOf(library).map { sagaItem(it, history) }
            id.startsWith("genre:") -> id.removePrefix("genre:").let { genre ->
                genre to (library.movies.filter { genre in it.genres }.map { movieItem(it, history) } + showItems(library.shows.filter { genre in it.genres }, history))
                    .sortedByDescending { it.rating ?: 0.0 }
            }
            else -> "" to emptyList()
        }
    }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenTitle(title, onBack)
        PosterGrid(
            items = items,
            emptyText = "Rien ici pour le moment.",
            onClick = { key ->
                when {
                    key.startsWith("saga:") -> key.removePrefix("saga:").toIntOrNull()?.let(links.onOpenSaga)
                    items.first { it.key == key }.isShow -> links.onOpenShow(key)
                    else -> links.onOpenMovie(key)
                }
            },
            noun = "titre",
            onLongClick = { item -> menu(if (item.key.startsWith("saga:")) TitleTarget(item.key) else TitleTarget(item.key, item.isShow)) },
        )
    }
}
