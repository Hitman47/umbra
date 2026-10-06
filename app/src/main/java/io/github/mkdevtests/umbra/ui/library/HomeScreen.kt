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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.browse.BrowserScreen
import io.github.mkdevtests.umbra.browse.BrowserViewModel
import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.history.seenOf
import io.github.mkdevtests.umbra.library.Library
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import io.github.mkdevtests.umbra.library.byTitle
import io.github.mkdevtests.umbra.library.decadePositions
import io.github.mkdevtests.umbra.library.letterPositions
import io.github.mkdevtests.umbra.library.LETTERS
import io.github.mkdevtests.umbra.ui.theme.INDEX_BAR_MIN_ITEMS
import io.github.mkdevtests.umbra.ui.theme.indexBarWidth
import io.github.mkdevtests.umbra.ui.theme.IndexBar
import io.github.mkdevtests.umbra.ui.theme.scaled
import io.github.mkdevtests.umbra.ui.theme.LocalCardScale
import io.github.mkdevtests.umbra.library.available
import io.github.mkdevtests.umbra.library.unavailableKeys
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Saga
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.library.decadeLabel
import io.github.mkdevtests.umbra.library.Universe
import io.github.mkdevtests.umbra.library.sagasOf
import io.github.mkdevtests.umbra.library.universesOf
import io.github.mkdevtests.umbra.library.kind
import io.github.mkdevtests.umbra.library.splitDocumentaries
import io.github.mkdevtests.umbra.history.without
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.NyxaraLogo
import io.github.mkdevtests.umbra.ui.theme.focusRing
import io.github.mkdevtests.umbra.update.UpdateBanner
import io.github.mkdevtests.umbra.update.Updater

enum class HomeTab(val label: String, val icon: ImageVector, val inBar: Boolean = true) {
    Home("Accueil", NyxaraIcons.Home),
    Movies("Films", NyxaraIcons.Movie),
    Shows("Séries", NyxaraIcons.Tv),
    Docs("Docs", NyxaraIcons.Explore),
    // Reached from the icons at the top on a phone: the bar keeps five tabs.
    Search("Recherche", NyxaraIcons.Search, inBar = false),
    Folders("Dossiers", NyxaraIcons.Folder, inBar = false),
    Perso("Perso", NyxaraIcons.Person),
}

/** The tabs of the bar (a phone) or the rail (a tablet, which has room for all); Docs once its folders are chosen. */
private fun tabsOf(wide: Boolean, documentaries: Boolean) =
    HomeTab.entries.filter { (wide || it.inBar) && (documentaries || it != HomeTab.Docs) }

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
    onOpenShelf: (String) -> Unit = {},
) {
    val settingsStore = (androidx.compose.ui.platform.LocalContext.current.applicationContext as io.github.mkdevtests.umbra.NyxaraApp).settings
    val settings by settingsStore.settings.collectAsState()
    val documentaries = settings.documentaryFolders.isNotEmpty()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val content: @Composable (Modifier) -> Unit = { modifier ->
            HomeContent(
                libraryViewModel, browserViewModel, updater, tab, wide,
                TitleLinks(onOpenMovie, onOpenShow, onOpenSaga, onOpenUniverse), onOpenShortcut, onPickLocalFile, onOpenSettings, modifier,
                documentaryFolders = settings.documentaryFolders,
                onTabChange = onTabChange,
                onOpenShelf = onOpenShelf,
            )
        }
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(modifier = Modifier.size(12.dp))
                    tabsOf(wide = true, documentaries).forEach { entry ->
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
                    tabsOf(wide = false, documentaries).forEach { entry ->
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
    documentaryFolders: List<String>,
    onTabChange: (HomeTab) -> Unit,
    onOpenShelf: (String) -> Unit,
) {
    val onOpenMovie = links.onOpenMovie
    val onOpenShow = links.onOpenShow
    val menu = rememberTitleMenu(libraryViewModel, links)
    val shortcuts by libraryViewModel.shortcuts.collectAsState()
    val fullLibrary by libraryViewModel.library.collectAsState()
    val hidden by libraryViewModel.hidden.collectAsState()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as io.github.mkdevtests.umbra.NyxaraApp
    val nasStates by app.nasMonitor.states.collectAsState()
    val nasHistory by app.nasMonitor.history.collectAsState()
    // Only the finished downloads matter here: a download's progress must not redraw the lists.
    val localCopies by remember { app.downloads.list.map { list -> list.filter { it.state == io.github.mkdevtests.umbra.download.DownloadState.Done }.mapTo(HashSet()) { it.key } }.distinctUntilChanged() }
        .collectAsState(initial = emptySet())
    val appSettings by app.settings.settings.collectAsState()
    val hide = appSettings.hideUnavailable
    val offlineIds = remember(nasStates) { nasStates.filter { !it.online }.mapTo(HashSet()) { it.id } }
    // A file of a NAS that doesn't answer, not downloaded: can't be read now.
    val unavailable: (String) -> Boolean = remember(offlineIds, localCopies) {
        val router = app.nas
        val local = localCopies
        val test: (String) -> Boolean = { file -> file !in local && router?.sourceOf(file)?.id in offlineIds }
        test
    }
    // Hidden when asked: out of every list, the search's included; else dimmed.
    val reachable = remember(fullLibrary, offlineIds, hide, unavailable) {
        if (hide && offlineIds.isNotEmpty()) fullLibrary.available(unavailable) else fullLibrary
    }
    val dimmed = remember(fullLibrary, offlineIds, hide, unavailable) {
        if (!hide && offlineIds.isNotEmpty()) fullLibrary.unavailableKeys(unavailable) else emptySet()
    }
    val titlesPerNas = remember(fullLibrary, nasStates) {
        val router = app.nas
        (fullLibrary.movies.map { it.file } + fullLibrary.shows.mapNotNull { show -> show.seasons.firstOrNull()?.episodes?.firstOrNull()?.file })
            .mapNotNull { router?.sourceOf(it)?.id }.groupingBy { it }.eachCount()
    }
    // Hidden titles stay out of every list; the search finds them with its "Masqués" filter.
    val library = remember(reachable, hidden) { reachable.without(hidden) }
    // Documentaries leave Films and Séries for their own tab.
    val (documentaries, titles) = remember(library, documentaryFolders) { library.splitDocumentaries(documentaryFolders) }
    val scan by libraryViewModel.scan.collectAsState()
    val history by libraryViewModel.history.collectAsState()
    val scanning = if (scan.running) "Analyse de la bibliothèque…" else null
    val tabStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

    Column(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NyxaraLogo()
            Box(modifier = Modifier.weight(1f))
            NasStatusChip(
                nasStates, wide, hide, titlesPerNas, nasHistory,
                onHide = { value -> app.settings.update { it.copy(hideUnavailable = value) } },
                onRetry = app.nasMonitor::checkNow,
            )
            if (scan.running) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = { app.nasMonitor.checkNow(); libraryViewModel.rescan() }) { Icon(NyxaraIcons.Refresh, contentDescription = "Actualiser la bibliothèque") }
            }
            if (wide) {
                IconButton(onClick = onPickLocalFile) { Icon(NyxaraIcons.Upload, contentDescription = "Lire un fichier de l'appareil") }
            } else {
                // A phone's bar keeps five tabs: Recherche and Dossiers are up here.
                IconButton(onClick = { onTabChange(HomeTab.Search) }) { Icon(NyxaraIcons.Search, contentDescription = "Recherche") }
                IconButton(onClick = { onTabChange(HomeTab.Folders) }) { Icon(NyxaraIcons.Folder, contentDescription = "Dossiers") }
                IconButton(onClick = onOpenSettings) { Icon(NyxaraIcons.Settings, contentDescription = "Réglages") }
            }
        }
        UpdateBanner(updater)
        TraktBatteryBanner()

        if (scan.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            scan.progress?.let { StatusText(it) }
        }
        scan.error?.let { StatusText(it, isError = true) }

        // Each tab keeps its filters and scroll while another tab or a page is shown.
        tabStates.SaveableStateProvider(tab.name) {
        androidx.compose.runtime.CompositionLocalProvider(LocalUnavailable provides dimmed) {
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
                onOpenShelf = onOpenShelf,
            )
            HomeTab.Movies -> MovieGrid(titles, history, scanning ?: "Aucun film trouvé.", links, menu)
            HomeTab.Shows -> ShowGrid(titles.shows, history, scanning ?: "Aucune série trouvée.", onOpenShow, menu)
            HomeTab.Docs -> DocumentaryGrid(documentaries, history, links, menu)
            HomeTab.Folders -> {
                val art by libraryViewModel.localArt.collectAsState()
                val context = androidx.compose.ui.platform.LocalContext.current
                BrowserScreen(
                    browserViewModel, fullLibrary, art, onOpenMovie, onOpenShow,
                    onPickLocalFile = onPickLocalFile.takeIf { !wide },
                    onExcluded = libraryViewModel::onFolderExcluded,
                    onPersonal = { (context.applicationContext as io.github.mkdevtests.umbra.NyxaraApp).addPersonal(it); browserViewModel.refresh() },
                )
            }
            HomeTab.Search -> SearchScreen(reachable, libraryViewModel, onOpenMovie, onOpenShow, onLongPress = menu)
            HomeTab.Perso -> io.github.mkdevtests.umbra.ui.perso.PersoScreen(androidx.lifecycle.viewmodel.compose.viewModel())
        }
        }
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
                GridSort.Title -> byTitle(kept) { it.title }
                GridSort.Added -> kept.sortedByDescending { it.added }
                GridSort.Year -> kept.sortedByDescending { it.year ?: 0 }
                GridSort.Rating -> kept.sortedByDescending { it.rating ?: 0.0 }
            }
        }
    }
    val filtered = seen != null || genre != null || decade != null
    val scale = LocalCardScale.current
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // In title order: the letters; in year order: the decades; else none.
    val index = remember(shown, sort) {
        when {
            shown.size < INDEX_BAR_MIN_ITEMS -> null
            sort == GridSort.Title -> LETTERS to letterPositions(shown.map { it.title })
            sort == GridSort.Year -> decadePositions(shown.map { it.year }).let { decades -> decades.keys.map { it.takeLast(2) } to decades.mapKeys { it.key.takeLast(2) } }
            else -> null
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 128.dp * scale),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp + if (index != null) indexBarWidth() else 0.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp * scale),
        verticalArrangement = Arrangement.spacedBy(20.dp * scale),
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
                        // All, not watched, watched: one row of three, the choice always visible.
                        val unwatched = items.count { !it.watched }
                        SingleChoiceSegmentedButtonRow {
                            listOf(null to "Tout", false to "Non vus · $unwatched", true to "Vus · ${items.size - unwatched}").forEachIndexed { index, (value, label) ->
                                SegmentedButton(
                                    selected = seen == value,
                                    onClick = { seen = value },
                                    shape = SegmentedButtonDefaults.itemShape(index, 3),
                                    icon = {},
                                    modifier = Modifier.focusRing(RoundedCornerShape(50)),
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                        chips()
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
    index?.let { (labels, positions) ->
        val header = if (noun != null) 1 else 0
        IndexBar(
            labels, positions,
            onJump = { position -> scope.launch { gridState.scrollToItem(position + header) } },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, bottom = 16.dp, end = 4.dp),
            // "90" in the bar, "1990" in the bubble.
            bubble = { label -> if (sort == GridSort.Year) positions[label]?.let { shown[it].year?.let { year -> (year / 10 * 10).toString() } } ?: label else label },
        )
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
    Column(modifier = modifier.focusRing().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        val unavailable = item.key in LocalUnavailable.current
        Box(modifier = if (unavailable) Modifier.alpha(0.4f) else Modifier) {
            Poster(item.poster, item.title, modifier = Modifier.fillMaxWidth())
            item.badge?.let { CornerBadge(it, Modifier.align(Alignment.TopEnd)) }
            if (unavailable) UnavailableMark(Modifier.align(Alignment.TopStart))
        }
        val scale = LocalCardScale.current
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium.scaled(scale),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp * scale),
        )
        val caption = listOfNotNull(item.year?.toString(), item.subtitle).joinToString(" · ")
        if (caption.isNotEmpty()) {
            Text(caption, style = MaterialTheme.typography.bodySmall.scaled(scale), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * Trakt failed for lack of network while Android's battery optimization
 * applies to Nyxara: says why, and lifts it in one touch.
 */
@Composable
private fun TraktBatteryBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val trakt = (context.applicationContext as io.github.mkdevtests.umbra.NyxaraApp).trakt
    val status by trakt.status.collectAsState()
    var dismissed by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        trakt.onBatteryExempted()
    }
    if (!status.batteryBlocked || dismissed) return
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Text("Trakt ne répond pas", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "L'économie d'énergie d'Android coupe le réseau de Nyxara dès qu'il quitte l'écran : la synchro et les épisodes vus ne passent plus. " +
                    "Autorise Nyxara à fonctionner sans restriction.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = {
                    val request = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { ask.launch(request) }
                        .onFailure { ask.launch(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }) { Text("Autoriser") }
                androidx.compose.material3.TextButton(onClick = { dismissed = true }) { Text("Plus tard") }
            }
        }
    }
}

/** The Séries tab: every show, or only the series, the animation or the anime (from TMDB's genres and origin). */
@Composable
private fun ShowGrid(shows: List<Show>, history: Map<String, Progress>, emptyText: String, onOpenShow: (String) -> Unit, menu: (TitleTarget) -> Unit) {
    var kind by rememberSaveable { mutableStateOf<io.github.mkdevtests.umbra.library.ShowKind?>(null) }
    val kinds = remember(shows) { shows.groupBy { it.kind() } }
    val shown = remember(shows, kind) { if (kind == null) shows else kinds[kind].orEmpty() }
    PosterGrid(
        items = showItems(shown, history),
        emptyText = emptyText,
        onClick = onOpenShow,
        noun = "série",
        onLongClick = { menu(TitleTarget(it.key, isShow = true)) },
        chips = {
            // Only kinds the library has; none when everything is of one kind.
            if (kinds.size > 1) {
                io.github.mkdevtests.umbra.library.ShowKind.entries.filter { it in kinds }.forEach { entry ->
                    FilterChip(selected = kind == entry, onClick = { kind = entry.takeIf { it != kind } }, label = { Text(entry.label) })
                }
            }
        },
    )
}

/** The Docs tab: the films and shows of the documentary folders, apart from the rest. */
@Composable
private fun DocumentaryGrid(documentaries: Library, history: Map<String, Progress>, links: TitleLinks, menu: (TitleTarget) -> Unit) {
    var only by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val items = remember(documentaries, history, only) {
        val movies = if (only != true) documentaries.movies.map { movieItem(it, history) } else emptyList()
        val shows = if (only != false) showItems(documentaries.shows, history) else emptyList()
        (movies + shows).sortedWith { a, b -> io.github.mkdevtests.umbra.browse.naturalCompare(a.title, b.title) }
    }
    PosterGrid(
        items = items,
        emptyText = "Aucun documentaire dans les dossiers choisis (Réglages › Documentaires).",
        onClick = { key -> if (items.first { it.key == key }.isShow) links.onOpenShow(key) else links.onOpenMovie(key) },
        noun = "documentaire",
        onLongClick = { menu(TitleTarget(it.key, it.isShow)) },
        chips = {
            if (documentaries.movies.isNotEmpty() && documentaries.shows.isNotEmpty()) {
                FilterChip(selected = only == false, onClick = { only = if (only == false) null else false }, label = { Text("Films") })
                FilterChip(selected = only == true, onClick = { only = if (only == true) null else true }, label = { Text("Séries") })
            }
        },
    )
}
