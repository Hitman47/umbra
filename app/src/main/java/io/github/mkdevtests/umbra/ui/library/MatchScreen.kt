package io.github.mkdevtests.umbra.ui.library

import io.github.mkdevtests.umbra.ui.theme.focusRing
import io.github.mkdevtests.umbra.ui.theme.remoteFriendly
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.library.TmdbSearchItem
import io.github.mkdevtests.umbra.library.TmdbSeasonSummary
import io.github.mkdevtests.umbra.library.parseMediaName

/**
 * "Corriger le matching": which TMDB show the folders of [show] are, and
 * optionally which season. The choice is kept and applied by every scan.
 */
@Composable
fun MatchScreen(show: Show, viewModel: LibraryViewModel, onBack: () -> Unit) {
    // Shows scanned before groups were kept: their own folders are their groups.
    val groups = remember(show) { show.groups.ifEmpty { show.folders.map { "folder:$it" } } }
    val fixes by viewModel.matchFixes.collectAsState()
    var selected by rememberSaveable(show.key) { mutableStateOf(groups.toSet()) }
    var query by rememberSaveable(show.key) { mutableStateOf(show.title) }
    var searched by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<TmdbSearchItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<TmdbSearchItem?>(null) }
    var seasons by remember { mutableStateOf<List<TmdbSeasonSummary>>(emptyList()) }
    var season by remember { mutableStateOf<Int?>(null) }
    var firstEpisode by remember { mutableIntStateOf(1) }

    LaunchedEffect(searched) {
        val text = searched?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        results = null
        error = null
        runCatching { viewModel.searchShows(text) }
            .onSuccess { results = it }
            .onFailure { error = "TMDB ne répond pas. Réessaie dans un instant." }
    }
    LaunchedEffect(chosen) {
        seasons = emptyList()
        season = null
        firstEpisode = 1
        val id = chosen?.id ?: return@LaunchedEffect
        seasons = runCatching { viewModel.seasonsOf(id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { searched = query }

    val apply = { tmdbId: Int? -> viewModel.fixMatch(selected.toList(), tmdbId, season, firstEpisode); onBack() }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Retour") }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text("Corriger le matching", style = MaterialTheme.typography.headlineSmall)
                Text(show.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(modifier = Modifier.fillMaxSize().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // Left: which folders the correction applies to.
            Column(modifier = Modifier.weight(1f)) {
                Text("Dossiers", style = MaterialTheme.typography.titleMedium)
                if (groups.size > 1) {
                    val all = selected.size == groups.size
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selected = if (all) emptySet() else groups.toSet() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = all, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text("Tous (${groups.size})")
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(groups, key = { it }) { group ->
                        val (name, parent) = groupLabel(group)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (group in selected) selected - group else selected + group
                                    // One folder picked: search its own name ("Nisemonogatari", not "Monogatari").
                                    if (selected.size == 1) query = parseMediaName(groupLabel(selected.single()).first).title
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = group in selected, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(parent, fixes[group]?.let { "corrigé" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedButton(onClick = { apply(null) }, enabled = selected.isNotEmpty()) { Text("Série à part, sans TMDB") }
                    OutlinedButton(
                        onClick = { viewModel.resetMatch(selected.toList()); onBack() },
                        enabled = selected.any { it in fixes },
                    ) { Text("Matching automatique") }
                }
            }

            // Right: the TMDB show, and the season if the folder is only part of it.
            Column(modifier = Modifier.weight(1.3f)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Titre sur TMDB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searched = query }),
                    trailingIcon = { TextButton(onClick = { searched = query }) { Text("Chercher") } },
                    modifier = Modifier.fillMaxWidth().remoteFriendly(),
                )
                val pick = chosen
                if (pick != null) {
                    ChosenShow(
                        pick, seasons, season, firstEpisode,
                        onSeason = { season = it; firstEpisode = 1 },
                        onFirstEpisode = { firstEpisode = it.coerceAtLeast(1) },
                        onCancel = { chosen = null },
                        onApply = { apply(pick.id) },
                        canApply = selected.isNotEmpty(),
                        folders = selected.size,
                    )
                } else {
                    // Read once: a new search empties the state while the list is still drawn.
                    val found = results
                    val failure = error
                    when {
                        failure != null -> Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
                        found == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        found.isEmpty() -> Text("Aucune série trouvée.", modifier = Modifier.padding(top = 16.dp))
                        else -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(found, key = { it.id }) { item ->
                                SearchResult(item, current = show.tmdbId == item.id) { chosen = item }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResult(item: TmdbSearchItem, current: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (current) 0.9f else 0.4f))
            .focusRing()
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(item.posterPath, item.name ?: "", modifier = Modifier.width(56.dp), size = "w185")
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name ?: "?", style = MaterialTheme.typography.titleMedium)
            val original = item.originalName?.takeIf { it != item.name }
            Text(
                listOfNotNull(item.firstAirDate?.take(4), original, if (current) "match actuel" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChosenShow(
    item: TmdbSearchItem,
    seasons: List<TmdbSeasonSummary>,
    season: Int?,
    firstEpisode: Int,
    onSeason: (Int?) -> Unit,
    onFirstEpisode: (Int) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    canApply: Boolean,
    folders: Int,
) {
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SearchResult(item, current = false, onClick = onCancel)
        Text("Saison", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = season == null, onClick = { onSeason(null) }, label = { Text("Celle des fichiers") })
            seasons.forEach { s ->
                val label = (if (s.number == 0) "Spéciaux" else "S${s.number}") +
                    (s.name?.takeIf { it != "Saison ${s.number}" && it != "Season ${s.number}" }?.let { " · $it" } ?: "") +
                    " · ${s.episodeCount} ép."
                FilterChip(selected = season == s.number, onClick = { onSeason(s.number) }, label = { Text(label) })
            }
        }
        if (season != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Le 1er fichier est l'épisode")
                OutlinedButton(onClick = { onFirstEpisode(firstEpisode - 1) }) { Text("−") }
                Text("E$firstEpisode", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { onFirstEpisode(firstEpisode + 1) }) { Text("+") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApply, enabled = canApply) {
                Text(if (folders > 1) "Appliquer aux $folders dossiers" else "Appliquer")
            }
            TextButton(onClick = onCancel) { Text("Autre série") }
        }
        Text(
            "La bibliothèque s'actualise pour appliquer le choix ; il reste valable aux prochains scans.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "folder:Animes\Monogatari\Bakemonogatari" → ("Bakemonogatari", "Animes › Monogatari"); "title:x" → its files. */
private fun groupLabel(group: String): Pair<String, String?> {
    val value = group.substringAfter(':')
    if (!group.startsWith("folder:")) return "Fichiers « $value »" to null
    val parts = value.split('\\')
    return parts.last() to parts.dropLast(1).takeLast(2).joinToString(" › ").ifEmpty { null }
}
