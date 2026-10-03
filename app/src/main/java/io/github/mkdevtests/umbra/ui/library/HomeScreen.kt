package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.browse.BrowserScreen
import io.github.mkdevtests.umbra.browse.BrowserViewModel
import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.history.seenOf
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Saga
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.library.decadeLabel
import io.github.mkdevtests.umbra.library.Universe
import io.github.mkdevtests.umbra.library.sagasOf
import io.github.mkdevtests.umbra.library.universesOf
import io.github.mkdevtests.umbra.history.without
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.NyxaraLogo
import io.github.mkdevtests.umbra.update.UpdateBanner
import io.github.mkdevtests.umbra.update.Updater

enum class HomeTab(val label: String, val icon: ImageVector) {
    Home("Accueil", NyxaraIcons.Home),
    Movies("Films", NyxaraIcons.Movie),
    Shows("Séries", NyxaraIcons.Tv),
    Search("Recherche", NyxaraIcons.Search),
    Folders("Dossiers", NyxaraIcons.Folder),
}

/** The library's screens under one navigation: a bar at the bottom of a phone, a rail on the side of a tablet. */
@Composable
fun HomeScreen(
    libraryViewModel: LibraryViewModel,
    browserViewModel: BrowserViewModel,
    updater: Updater,
    tab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenShow: (String) -> Unit,
    onOpenSaga: (Int) -> Unit,
    onOpenUniverse: (String) -> Unit,
    onOpenShortcut: (String) -> Unit,
    onPickLocalFile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val content: @Composable (Modifier) -> Unit = { modifier ->
            HomeContent(
                libraryViewModel, browserViewModel, updater, tab, wide,
                TitleLinks(onOpenMovie, onOpenShow, onOpenSaga, onOpenUniverse), onOpenShortcut, onPickLocalFile, onOpenSettings, modifier,
            )
        }
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(modifier = Modifier.size(12.dp))
                    HomeTab.entries.forEach { entry ->
                        NavigationRailItem(
                            selected = entry == tab,
                            onClick = { onTabChange(entry) },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            label = { Text(entry.label) },
                            colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    NavigationRailItem(
                        selected = false,
                        onClick = onOpenSettings,
                        icon = { Icon(NyxaraIcons.Settings, contentDescription = null) },
                        label = { Text("Réglages") },
                    )
                }
                content(Modifier.weight(1f))
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                content(Modifier.weight(1f))
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    HomeTab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = entry == tab,
                            onClick = { onTabChange(entry) },
                            icon = { Icon(entry.icon, contentDescription = null) },
                            label = { Text(entry.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeContent(
    libraryViewModel: LibraryViewModel,
    browserViewModel: BrowserViewModel,
    updater: Updater,
    tab: HomeTab,
    wide: Boolean,
    links: TitleLinks,
    onOpenShortcut: (String) -> Unit,
    onPickLocalFile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val onOpenMovie = links.onOpenMovie
    val onOpenShow = links.onOpenShow
    val menu = rememberTitleMenu(libraryViewModel, links)
    val shortcuts by libraryViewModel.shortcuts.collectAsState()
    val fullLibrary by libraryViewModel.library.collectAsState()
    val hidden by libraryViewModel.hidden.collectAsState()
    // Hidden titles stay out of every list; the search finds them with its "Masqués" filter.
    val library = remember(fullLibrary, hidden) { fullLibrary.without(hidden) }
    val scan by libraryViewModel.scan.collectAsState()
    val history by libraryViewModel.history.collectAsState()
    val scanning = if (scan.running) "Analyse de la bibliothèque…" else null

    Column(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NyxaraLogo()
            Box(modifier = Modifier.weight(1f))
            if (scan.running) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = libraryViewModel::rescan) { Icon(NyxaraIcons.Refresh, contentDescription = "Actualiser la bibliothèque") }
            }
            IconButton(onClick = onPickLocalFile) { Icon(NyxaraIcons.Upload, contentDescription = "Lire un fichier de l'appareil") }
            if (!wide) IconButton(onClick = onOpenSettings) { Icon(NyxaraIcons.Settings, contentDescription = "Réglages") }
        }
        UpdateBanner(updater)

        if (scan.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            scan.progress?.let { StatusText(it) }
        }
        scan.error?.let { StatusText(it, isError = true) }

        when (tab) {
            HomeTab.Home -> FeedScreen(
                library = library,
                scanning = scan.running,
                history = history,
                viewModel = libraryViewModel,
                wide = wide,
                emptyText = scanning ?: "Aucune vidéo trouvée.",
                links = links,
                shortcuts = shortcuts,
                onOpenShortcut = onOpenShortcut,
                onLongPress = menu,
            )
            HomeTab.Movies -> MovieGrid(library, history, scanning ?: "Aucun film trouvé.", links, menu)
            HomeTab.Shows -> PosterGrid(
                items = showItems(library.shows, history),
                emptyText = scanning ?: "Aucune série trouvée.",
                onClick = onOpenShow,
                noun = "série",
                onLongClick = { menu(TitleTarget(it.key, isShow = true)) },
            )
            HomeTab.Folders -> {
                val art by libraryViewModel.localArt.collectAsState()
                BrowserScreen(browserViewModel, fullLibrary, art, onOpenMovie, onOpenShow, onExcluded = libraryViewModel::onFolderExcluded)
            }
            HomeTab.Search -> SearchScreen(fullLibrary, libraryViewModel, onOpenMovie, onOpenShow, onLongPress = menu)
        }
    }
}

@Composable
private fun StatusText(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

internal data class PosterItem(
    val key: String,
    val title: String,
    val year: Int?,
    val poster: String?,
    val subtitle: String? = null,
    /** "✓ Vu" or "3/10", in the poster's corner. */
    val badge: String? = null,
    /** When its newest file arrived on the NAS, to sort by. */
    val added: Long = 0,
    val rating: Double? = null,
    val isShow: Boolean = false,
    val genres: List<String> = emptyList(),
    /** Watched to the end (every episode of a show): the "Vus" filter. */
    val watched: Boolean = false,
)

internal fun movieItem(movie: Movie, history: Map<String, Progress>): PosterItem {
    val seen = seenOf(movie, history)
    return PosterItem(movie.file, movie.title, movie.year, movie.poster, badge = seen.badge, added = movie.modified, rating = movie.rating, genres = movie.genres, watched = seen.all)
}

internal fun showItems(shows: List<Show>, history: Map<String, Progress>) = shows.map { show ->
    val episodes = show.seasons.flatMap { it.episodes }
    val seen = seenOf(show, history)
    PosterItem(
        show.key, show.title, show.year, show.poster,
        subtitle = "${episodes.size} épisode${if (episodes.size > 1) "s" else ""}",
        badge = seen.badge,
        added = episodes.maxOfOrNull { it.modified } ?: 0,
        rating = show.rating,
        isShow = true,
        genres = show.genres,
        watched = seen.all,
    )
}

internal fun sagaItem(saga: Saga, history: Map<String, Progress>): PosterItem {
    val watched = saga.movies.count { history[it.file]?.watched == true }
    return PosterItem(
        "saga:${saga.id}", saga.name, saga.movies.first().year, saga.poster,
        subtitle = "${saga.movies.size} films",
        badge = if (watched == saga.movies.size) "✓ Vu" else if (watched > 0) "$watched/${saga.movies.size}" else null,
        added = saga.movies.maxOf { it.modified },
        rating = saga.movies.mapNotNull { it.rating }.average().takeIf { !it.isNaN() },
        genres = saga.movies.flatMap { it.genres }.distinct(),
        watched = watched == saga.movies.size,
    )
}

/** What the "Films" tab shows. */
private enum class MovieView { Films, Sagas, Universes }

/** The "Films" tab: films, their sagas, or the universes their characters make. */
@Composable
private fun MovieGrid(library: Library, history: Map<String, Progress>, emptyText: String, links: TitleLinks, menu: (TitleTarget) -> Unit) {
    var view by rememberSaveable { mutableStateOf(MovieView.Films) }
    val sagas = remember(library) { sagasOf(library) }
    val universes = remember(library) { universesOf(library) }
    val items = remember(library, history, view) {
        when (view) {
            MovieView.Films -> library.movies.map { movieItem(it, history) }
            MovieView.Sagas -> sagas.map { sagaItem(it, history) }
            MovieView.Universes -> universes.map { universeItem(it, history) }
        }
    }
    PosterGrid(
        items = items,
        emptyText = emptyText,
        onClick = { key ->
            when {
                key.startsWith("saga:") -> key.removePrefix("saga:").toIntOrNull()?.let(links.onOpenSaga)
                key.startsWith(UNIVERSE) -> links.onOpenUniverse(key.removePrefix(UNIVERSE))
                else -> links.onOpenMovie(key)
            }
        },
        noun = when (view) {
            MovieView.Films -> "film"
            MovieView.Sagas -> "saga"
            MovieView.Universes -> "univers"
        },
        onLongClick = { item -> if (!item.key.startsWith(UNIVERSE)) menu(TitleTarget(item.key)) },
        chips = {
            if (sagas.isNotEmpty()) {
                FilterChip(selected = view == MovieView.Sagas, onClick = { view = if (view == MovieView.Sagas) MovieView.Films else MovieView.Sagas }, label = { Text("Sagas") })
            }
            if (universes.isNotEmpty()) {
                FilterChip(selected = view == MovieView.Universes, onClick = { view = if (view == MovieView.Universes) MovieView.Films else MovieView.Universes }, label = { Text("Univers") })
            }
        },
    )
}

/** Key prefix of a universe's poster: "universe:Batman". */
private const val UNIVERSE = "universe:"

internal fun universeItem(universe: Universe, history: Map<String, Progress>): PosterItem {
    val seen = universe.titles.count { title ->
        title.movie?.let { history[it]?.watched == true } ?: false
    }
    return PosterItem(
        UNIVERSE + universe.name, universe.name, universe.titles.first().year, universe.poster,
        subtitle = "${universe.titles.size} titres",
        badge = if (seen > 0) "$seen/${universe.titles.size}" else null,
        added = 0,
        rating = universe.titles.mapNotNull { it.rating }.average().takeIf { !it.isNaN() },
    )
}

/** Orders of a poster grid. */
internal enum class GridSort(val label: String) { Title("Titre"), Added("Ajouts récents"), Year("Année"), Rating("Note") }

/**
 * A grid of posters. With a [noun], a header counts them ("212 films"),
 * offers to sort and filter them (watched, genre, decade, and [chips]); the
 * choices are kept per grid.
 */
@Composable
internal fun PosterGrid(
    items: List<PosterItem>,
    emptyText: String,
    onClick: (String) -> Unit,
    noun: String? = null,
    onLongClick: ((PosterItem) -> Unit)? = null,
    chips: @Composable () -> Unit = {},
) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var sort by rememberSaveable(noun) { mutableStateOf(GridSort.Title) }
    var seen by rememberSaveable(noun) { mutableStateOf<Boolean?>(null) }
    var genre by rememberSaveable(noun) { mutableStateOf<String?>(null) }
    var decade by rememberSaveable(noun) { mutableStateOf<Int?>(null) }
    val genres = remember(items) { items.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value } }
    val decades = remember(items) { items.mapNotNull { it.year?.let { year -> year / 10 * 10 } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.key }.map { it.key to it.value } }
    val shown = remember(items, sort, seen, genre, decade) {
        items.filter { item ->
            (seen == null || item.watched == seen) &&
                (genre == null || genre in item.genres) &&
                (decade == null || item.year?.let { it / 10 * 10 } == decade)
        }.let { kept ->
            when (sort) {
                GridSort.Title -> kept
                GridSort.Added -> kept.sortedByDescending { it.added }
                GridSort.Year -> kept.sortedByDescending { it.year ?: 0 }
                GridSort.Rating -> kept.sortedByDescending { it.rating ?: 0.0 }
            }
        }
    }
    val filtered = seen != null || genre != null || decade != null
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (noun != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${shown.size} $noun${if (shown.size > 1 && !noun.endsWith("s")) "s" else ""}" + if (filtered) " sur ${items.size}" else "",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        SortMenu(sort) { sort = it }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        chips()
                        listOf(true to "Vus", false to "Non vus").forEach { (value, label) ->
                            FilterChip(selected = seen == value, onClick = { seen = value.takeIf { it != seen } }, label = { Text(label) })
                        }
                        if (genres.size > 1) {
                            Dropdown(
                                text = genre ?: "Genre",
                                active = genre != null,
                                options = listOf<Pair<String?, String>>(null to "Tous les genres") + genres.map { (name, count) -> name to "$name ($count)" },
                                onSelect = { genre = it },
                            )
                        }
                        if (decades.size > 1) {
                            Dropdown(
                                text = decade?.let(::decadeLabel) ?: "Années",
                                active = decade != null,
                                options = listOf<Pair<Int?, String>>(null to "Toutes les années") + decades.map { (value, count) -> value to "${decadeLabel(value)} ($count)" },
                                onSelect = { decade = it },
                            )
                        }
                        if (filtered) TextButton(onClick = { seen = null; genre = null; decade = null }) { Text("Effacer") }
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Aucun titre pour ces filtres.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 32.dp))
            }
        }
        items(shown, key = { it.key }) { item ->
            PosterCard(item, onClick = { onClick(item.key) }, onLongClick = onLongClick?.let { { it(item) } })
        }
    }
}

@Composable
private fun SortMenu(sort: GridSort, onSort: (GridSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = sort != GridSort.Title,
            onClick = { open = true },
            leadingIcon = { Icon(NyxaraIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(sort.label) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GridSort.entries.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.label) }, onClick = { open = false; onSort(entry) })
            }
        }
    }
}

/** A poster with its title and year below, its badge in the corner. A long press opens [onLongClick]'s menu. */
@Composable
internal fun PosterCard(item: PosterItem, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Column(modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            Poster(item.poster, item.title, modifier = Modifier.fillMaxWidth())
            item.badge?.let { CornerBadge(it, Modifier.align(Alignment.TopEnd)) }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        val caption = listOfNotNull(item.year?.toString(), item.subtitle).joinToString(" · ")
        if (caption.isNotEmpty()) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
