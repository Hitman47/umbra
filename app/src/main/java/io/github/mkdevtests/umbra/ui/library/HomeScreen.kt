package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.browse.BrowserScreen
import io.github.mkdevtests.umbra.browse.BrowserViewModel

enum class HomeTab(val label: String) { Movies("Films"), Shows("Séries"), Folders("Dossiers") }

@Composable
fun HomeScreen(
    libraryViewModel: LibraryViewModel,
    browserViewModel: BrowserViewModel,
    tab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenShow: (String) -> Unit,
    onPickLocalFile: () -> Unit,
    onEditSource: () -> Unit,
) {
    val library by libraryViewModel.library.collectAsState()
    val scan by libraryViewModel.scan.collectAsState()

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Umbra", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = libraryViewModel::rescan, enabled = !scan.running) { Text("Actualiser") }
            TextButton(onClick = onPickLocalFile) { Text("Fichier local") }
            TextButton(onClick = onEditSource) { Text("NAS") }
        }
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HomeTab.entries.forEach { entry ->
                FilterChip(selected = entry == tab, onClick = { onTabChange(entry) }, label = { Text(entry.label) })
            }
        }

        if (scan.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            scan.progress?.let { StatusText(it) }
        }
        scan.error?.let { StatusText(it, isError = true) }

        when (tab) {
            HomeTab.Movies -> PosterGrid(
                items = library.movies.map { PosterItem(it.file, it.title, it.year, it.poster) },
                emptyText = if (scan.running) "Analyse de la bibliothèque…" else "Aucun film trouvé.",
                onClick = onOpenMovie,
            )
            HomeTab.Shows -> PosterGrid(
                items = library.shows.map { show ->
                    val episodes = show.seasons.sumOf { it.episodes.size }
                    PosterItem(show.key, show.title, show.year, show.poster, "$episodes épisode${if (episodes > 1) "s" else ""}")
                },
                emptyText = if (scan.running) "Analyse de la bibliothèque…" else "Aucune série trouvée.",
                onClick = onOpenShow,
            )
            HomeTab.Folders -> BrowserScreen(browserViewModel)
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

private data class PosterItem(val key: String, val title: String, val year: Int?, val poster: String?, val subtitle: String? = null)

@Composable
private fun PosterGrid(items: List<PosterItem>, emptyText: String, onClick: (String) -> Unit) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.key }) { item ->
            Column(modifier = Modifier.clickable { onClick(item.key) }) {
                Poster(item.poster, item.title, modifier = Modifier.fillMaxWidth())
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    listOfNotNull(item.year?.toString(), item.subtitle).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
