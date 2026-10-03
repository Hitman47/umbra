package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.library.Tmdb
import java.util.Locale

@Composable
fun MovieDetailScreen(movie: Movie, viewModel: LibraryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
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
                Button(onClick = { context.startActivity(viewModel.playIntent(movie)) }) { Text("▶  Lecture") }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                movie.tagline?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic) }
                movie.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                FileInfo(movie.file, movie.fileSize)
            }
        }
    }
}

@Composable
fun ShowDetailScreen(show: Show, viewModel: LibraryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by rememberSaveable(show.key) { mutableIntStateOf(show.seasons.firstOrNull { it.number > 0 }?.number ?: show.seasons.firstOrNull()?.number ?: 1) }
    val season = show.seasons.firstOrNull { it.number == selected }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { DetailHeader(show.backdrop, onBack) }
        item {
            val episodes = show.seasons.sumOf { it.episodes.size }
            TitleBlock(
                poster = show.poster,
                title = show.title,
                originalTitle = show.originalTitle,
                meta = listOfNotNull(show.year?.toString(), "$episodes épisodes", formatRating(show.rating)),
                genres = show.genres,
            ) {
                val next = season?.episodes?.firstOrNull()
                if (next != null) {
                    Button(onClick = { context.startActivity(viewModel.playIntent(show, next)) }) {
                        Text("▶  S%02dE%02d".format(next.season, next.number))
                    }
                }
            }
        }
        item {
            show.overview?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 24.dp))
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
                        label = { Text(if (it.number == 0) "Spéciaux" else "Saison ${it.number}") },
                    )
                }
            }
        }
        items(season?.episodes.orEmpty(), key = { "${it.season}-${it.number}" }) { episode ->
            EpisodeRow(episode) { context.startActivity(viewModel.playIntent(show, episode)) }
        }
    }
}

@Composable
private fun DetailHeader(backdrop: String?, onBack: () -> Unit) {
    Box {
        Backdrop(backdrop)
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .safeDrawingPadding()
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50)),
        ) { Text("← Retour", color = Color.White) }
    }
}

@Composable
private fun TitleBlock(
    poster: String?,
    title: String,
    originalTitle: String?,
    meta: List<String>,
    genres: List<String>,
    action: @Composable () -> Unit,
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
            Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            if (genres.isNotEmpty()) {
                Text(genres.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(modifier = Modifier.padding(top = 6.dp)) { action() }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, onClick: () -> Unit) {
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
