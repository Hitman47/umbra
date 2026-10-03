package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.history.hideKey
import io.github.mkdevtests.umbra.home.nextUp
import io.github.mkdevtests.umbra.home.regularEpisodes
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Extras
import io.github.mkdevtests.umbra.library.Person
import io.github.mkdevtests.umbra.library.Related
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.library.Tmdb
import io.github.mkdevtests.umbra.ui.theme.GlassButton
import io.github.mkdevtests.umbra.ui.theme.GlassIconButton
import io.github.mkdevtests.umbra.ui.theme.GlowButton
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.Tag
import java.util.Locale

/** Where a page leads: another title of the library, or the search for a person. */
class DetailLinks(
    val onOpenMovie: (String) -> Unit,
    val onOpenShow: (String) -> Unit,
    val onPerson: (String) -> Unit,
)

@Composable
fun MovieDetailScreen(movie: Movie, viewModel: LibraryViewModel, links: DetailLinks, onBack: () -> Unit) {
    val context = LocalContext.current
    var extras by remember(movie.file) { mutableStateOf<Extras?>(null) }
    LaunchedEffect(movie.file) { extras = viewModel.extras(movie) }
    val history by viewModel.history.collectAsState()
    val progress = history[movie.file]
    val hidden by viewModel.hidden.collectAsState()
    val isHidden = movie.hideKey in hidden
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { DetailHeader(movie.backdrop, onBack) }
        item {
            TitleBlock(
                poster = movie.poster,
                title = movie.title,
                originalTitle = movie.originalTitle,
                meta = listOfNotNull(movie.year?.toString(), formatRuntime(movie.runtime), formatRating(movie.rating)),
                genres = movie.genres,
            ) {
                if (progress?.inProgress == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlowButton(
                            "Reprendre · il reste ${formatRuntime((progress.remaining / 60).toInt().coerceAtLeast(1))}",
                            onClick = { context.startActivity(viewModel.playIntent(movie)) },
                        )
                        GlassButton("Depuis le début", onClick = { context.startActivity(viewModel.playIntent(movie, fromStart = true)) })
                    }
                } else {
                    GlowButton(if (progress?.watched == true) "Revoir" else "Lecture", onClick = { context.startActivity(viewModel.playIntent(movie)) })
                }
                HideButton(isHidden) { viewModel.setHidden(movie, !isHidden) }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                movie.tagline?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic) }
                movie.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (extras == null) Credits("Réalisation", movie.directors, movie.cast)
            }
        }
        extras?.let { item { ExtrasRows(it, links) } }
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) { FileInfo(movie.file, movie.fileSize) }
        }
    }
}

@Composable
fun ShowDetailScreen(show: Show, viewModel: LibraryViewModel, links: DetailLinks, onBack: () -> Unit, onFixMatch: () -> Unit) {
    val context = LocalContext.current
    var extras by remember(show.key) { mutableStateOf<Extras?>(null) }
    LaunchedEffect(show.key) { extras = viewModel.extras(show) }
    var selected by rememberSaveable(show.key) { mutableIntStateOf(show.seasons.firstOrNull { it.number > 0 }?.number ?: show.seasons.firstOrNull()?.number ?: 1) }
    val season = show.seasons.firstOrNull { it.number == selected }
    val history by viewModel.history.collectAsState()
    val hidden by viewModel.hidden.collectAsState()
    val isHidden = show.hideKey in hidden

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { DetailHeader(show.backdrop, onBack) }
        item {
            val episodes = show.seasons.sumOf { it.episodes.size }
            TitleBlock(
                poster = show.poster,
                title = show.title,
                originalTitle = show.originalTitle,
                meta = listOfNotNull(show.year?.toString(), statusLabel(show.status), seasonsOwned(show), "$episodes épisodes", formatRating(show.rating)),
                genres = show.genres,
            ) {
                val resume = nextUp(show, history)
                val next = resume?.episode ?: show.regularEpisodes().firstOrNull() ?: season?.episodes?.firstOrNull()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (next != null) {
                        val code = "S%02dE%02d".format(next.season, next.number)
                        GlowButton(if (resume?.progress != null) "Reprendre $code" else "Lecture $code", onClick = { context.startActivity(viewModel.playIntent(show, next)) })
                    }
                    GlassButton("Corriger", onClick = onFixMatch, icon = NyxaraIcons.Edit)
                }
                HideButton(isHidden) { viewModel.setHidden(show, !isHidden) }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                show.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (extras == null) Credits("Création", show.directors, show.cast)
            }
        }
        item {
            LazyRow(
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(show.seasons, key = { it.number }) {
                    FilterChip(
                        selected = it.number == selected,
                        onClick = { selected = it.number },
                        label = { Text((if (it.number == 0) "Spéciaux" else "Saison ${it.number}") + episodesOwned(show, it.number, it.episodes.size)) },
                    )
                }
            }
        }
        items(season?.episodes.orEmpty(), key = { "${it.season}-${it.number}" }) { episode ->
            EpisodeRow(episode, history[episode.file]) { context.startActivity(viewModel.playIntent(show, episode)) }
        }
        extras?.let { item { Column(modifier = Modifier.padding(bottom = 32.dp)) { ExtrasRows(it, links) } } }
    }
}

/** Cast and crew with their photos, the saga, the library's related titles and TMDB's recommendations. */
@Composable
private fun ExtrasRows(extras: Extras, links: DetailLinks) {
    Column {
        PeopleRow("Distribution", extras.cast, links.onPerson)
        PeopleRow("Équipe", extras.crew, links.onPerson)
        RelatedRow(extras.saga ?: "Saga", extras.sagaParts, links)
        RelatedRow("Dans la bibliothèque", extras.linked, links)
        RelatedRow("Titres similaires", extras.recommended, links)
    }
}

@Composable
private fun RowTitle(title: String) {
    io.github.mkdevtests.umbra.ui.theme.SectionHeader(title, modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp))
}

/** People in a row; a touch searches the library for them. */
@Composable
private fun PeopleRow(title: String, people: List<Person>, onPerson: (String) -> Unit) {
    if (people.isEmpty()) return
    RowTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(people) { person ->
            Column(
                modifier = Modifier.width(92.dp).clickable { onPerson(person.name) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (person.photo != null) {
                        AsyncImage(
                            model = Tmdb.image(person.photo, "w185"),
                            contentDescription = person.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(
                    person.name,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                person.role?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Posters in a row; titles missing from the library are dimmed and don't open. */
@Composable
private fun RelatedRow(title: String, items: List<Related>, links: DetailLinks) {
    if (items.isEmpty()) return
    RowTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { item ->
            val open = item.movie?.let { { links.onOpenMovie(it) } } ?: item.show?.let { { links.onOpenShow(it) } }
            Column(
                modifier = Modifier
                    .width(110.dp)
                    .alpha(if (item.owned) 1f else 0.45f)
                    .then(if (open != null) Modifier.clickable(onClick = open) else Modifier),
            ) {
                Poster(item.poster, item.title, modifier = Modifier.fillMaxWidth(), size = "w185")
                Text(item.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(
                    listOfNotNull(item.year?.toString(), "absent".takeIf { !item.owned }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Hides the title from the lists, or shows it again. */
@Composable
private fun HideButton(hidden: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(if (hidden) NyxaraIcons.Show else NyxaraIcons.Hide, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(if (hidden) "  Ne plus masquer" else "  Masquer ce titre")
    }
}

/** "Réalisation : Denis Villeneuve", "Avec : Timothée Chalamet, Zendaya…". */
@Composable
private fun Credits(directorsLabel: String, directors: List<String>, cast: List<String>) {
    if (directors.isNotEmpty()) CreditLine(directorsLabel, directors.joinToString(", "))
    if (cast.isNotEmpty()) CreditLine("Avec", cast.take(6).joinToString(", "))
}

@Composable
private fun CreditLine(label: String, names: String) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant))
            append("$label : ")
            pop()
            append(names)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** TMDB status in French; null when TMDB doesn't say. */
private fun statusLabel(status: String?): String? = when (status) {
    "Returning Series" -> "En cours"
    "Ended" -> "Terminée"
    "Canceled" -> "Annulée"
    "In Production", "Planned", "Pilot" -> "En production"
    else -> null
}

/** "3 saisons sur 5": regular seasons with files, out of those on TMDB. */
private fun seasonsOwned(show: Show): String? {
    val owned = show.seasons.count { it.number > 0 && it.episodes.isNotEmpty() }
    val total = show.seasonEpisodes.keys.count { it > 0 }
    if (owned == 0) return null
    val noun = if (owned > 1) "saisons" else "saison"
    return if (total > owned) "$owned $noun sur $total" else "$owned $noun"
}

/** " · 8/12" on a season chip when files are missing. */
private fun episodesOwned(show: Show, season: Int, owned: Int): String {
    val total = show.seasonEpisodes[season] ?: return ""
    return if (season > 0 && total > owned) " · $owned/$total" else ""
}

@Composable
private fun DetailHeader(backdrop: String?, onBack: () -> Unit) {
    Box {
        Backdrop(backdrop)
        GlassIconButton(NyxaraIcons.Back, "Retour", onBack, modifier = Modifier.safeDrawingPadding().padding(12.dp))
    }
}

@Composable
private fun TitleBlock(
    poster: String?,
    title: String,
    originalTitle: String?,
    meta: List<String>,
    genres: List<String>,
    action: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().offset(y = (-72).dp).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Poster(poster, title, modifier = Modifier.width(150.dp), size = "w500")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (originalTitle != null && originalTitle != title) {
                Text(originalTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (genres.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { genres.take(3).forEach { Tag(it) } }
            }
            Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { action() }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, progress: Progress?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .width(220.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (episode.still != null) {
                AsyncImage(
                    model = Tmdb.image(episode.still, "w300"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("E${episode.number}", style = MaterialTheme.typography.titleMedium)
            }
            if (progress?.inProgress == true) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp),
                    drawStopIndicator = {},
                )
            }
            if (progress?.watched == true) CornerBadge("✓ Vu", Modifier.align(Alignment.TopEnd))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${episode.number}. ${episode.title ?: "Épisode ${episode.number}"}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(formatRuntime(episode.runtime), episode.airDate?.let(::formatDate))
            if (meta.isNotEmpty()) {
                Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            episode.overview?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FileInfo(file: String, size: Long) {
    Text(
        "${file.substringAfterLast('\\')}  ·  ${String.format(Locale.FRANCE, "%.1f Go", size / (1L shl 30).toDouble())}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 32.dp),
    )
}

/** "2019-04-14" -> "14/04/2019". */
private fun formatDate(iso: String): String = iso.split('-').takeIf { it.size == 3 }?.reversed()?.joinToString("/") ?: iso
