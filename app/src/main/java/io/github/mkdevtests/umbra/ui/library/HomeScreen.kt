package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.clickable
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
import io.github.mkdevtests.umbra.history.seenOf
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
    onPickLocalFile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val content: @Composable (Modifier) -> Unit = { modifier ->
            HomeContent(libraryViewModel, browserViewModel, updater, tab, wide, onOpenMovie, onOpenShow, onPickLocalFile, onOpenSettings, modifier)
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
    onOpenMovie: (String) -> Unit,
    onOpenShow: (String) -> Unit,
    onPickLocalFile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
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
                onOpenMovie = onOpenMovie,
                onOpenShow = onOpenShow,
            )
            HomeTab.Movies -> PosterGrid(
                items = library.movies.map {
                    PosterItem(it.file, it.title, it.year, it.poster, badge = seenOf(it, history).badge, added = it.modified, rating = it.rating)
                },
                emptyText = scanning ?: "Aucun film trouvé.",
                onClick = onOpenMovie,
                noun = "film",
            )
            HomeTab.Shows -> PosterGrid(
                items = library.shows.map { show ->
                    val episodes = show.seasons.flatMap { it.episodes }
                    PosterItem(
                        show.key, show.title, show.year, show.poster,
                        subtitle = "${episodes.size} épisode${if (episodes.size > 1) "s" else ""}",
                        badge = seenOf(show, history).badge,
                        added = episodes.maxOfOrNull { it.modified } ?: 0,
                        rating = show.rating,
                    )
                },
                emptyText = scanning ?: "Aucune série trouvée.",
                onClick = onOpenShow,
                noun = "série",
            )
            HomeTab.Folders -> BrowserScreen(browserViewModel, fullLibrary, onOpenMovie, onOpenShow, onExcluded = libraryViewModel::onFolderExcluded)
            HomeTab.Search -> SearchScreen(fullLibrary, libraryViewModel, onOpenMovie, onOpenShow)
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
)

/** Orders of a poster grid. */
internal enum class GridSort(val label: String) { Title("Titre"), Added("Ajouts récents"), Year("Année"), Rating("Note") }

/**
 * A grid of posters. With a [noun], a header counts them ("212 films") and
 * offers to sort them; the order is kept per grid.
 */
@Composable
internal fun PosterGrid(items: List<PosterItem>, emptyText: String, onClick: (String) -> Unit, noun: String? = null) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var sort by rememberSaveable(noun) { mutableStateOf(GridSort.Title) }
    val sorted = remember(items, sort) {
        when (sort) {
            GridSort.Title -> items
            GridSort.Added -> items.sortedByDescending { it.added }
            GridSort.Year -> items.sortedByDescending { it.year ?: 0 }
            GridSort.Rating -> items.sortedByDescending { it.rating ?: 0.0 }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 128.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (noun != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${items.size} $noun${if (items.size > 1) "s" else ""}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    SortMenu(sort) { sort = it }
                }
            }
        }
        items(sorted, key = { it.key }) { item ->
            PosterCard(item, onClick = { onClick(item.key) })
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

/** A poster with its title and year below, its badge in the corner. */
@Composable
internal fun PosterCard(item: PosterItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
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
