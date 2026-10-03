package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.home.Pick
import io.github.mkdevtests.umbra.home.Resume
import io.github.mkdevtests.umbra.home.atRandom
import io.github.mkdevtests.umbra.home.continueWatching
import io.github.mkdevtests.umbra.home.forYou
import io.github.mkdevtests.umbra.home.newest
import io.github.mkdevtests.umbra.home.taste
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Tmdb
import kotlin.random.Random

/** The "Accueil" tab: what to resume, what's new, and a selection of films and shows. */
@Composable
fun FeedScreen(
    library: Library,
    scanning: Boolean,
    history: Map<String, Progress>,
    viewModel: LibraryViewModel,
    emptyText: String,
    onOpenMovie: (String) -> Unit,
    onOpenShow: (String) -> Unit,
) {
    val context = LocalContext.current
    // A scan publishes the library many times: keep the rows still until it ends,
    // or a poster moves under the finger.
    var shown by remember { mutableStateOf(library) }
    LaunchedEffect(library, scanning) {
        if (!scanning || (shown.movies.isEmpty() && shown.shows.isEmpty())) shown = library
    }
    val library = shown
    val resume = remember(library, history) { continueWatching(library, history) }
    val fresh = remember(library) { newest(library) }
    val taste = remember(library, history) { taste(library, history) }
    var movieMode by rememberSaveable { mutableStateOf(Selection.ForYou) }
    var movieSeed by rememberSaveable { mutableLongStateOf(Random.nextLong()) }
    var showMode by rememberSaveable { mutableStateOf(Selection.ForYou) }
    var showSeed by rememberSaveable { mutableLongStateOf(Random.nextLong()) }
    // Titles already started belong to "Lecture en cours", not to the selections.
    val movies = remember(library, history, movieMode, movieSeed) {
        val candidates = library.movies.filter { it.file !in history }
        when (movieMode) {
            Selection.ForYou -> forYou(candidates, taste, { it.genres }, { it.rating }, movieSeed)
            Selection.Random -> atRandom(candidates, movieSeed)
        }.map { Pick(it.file, it.title, it.poster, isShow = false) }
    }
    val shows = remember(library, history, showMode, showSeed) {
        val candidates = library.shows.filter { show -> show.seasons.none { season -> season.episodes.any { it.file in history } } }
        when (showMode) {
            Selection.ForYou -> forYou(candidates, taste, { it.genres }, { it.rating }, showSeed)
            Selection.Random -> atRandom(candidates, showSeed)
        }.map { Pick(it.key, it.title, it.poster, isShow = true) }
    }
    val open = { pick: Pick -> if (pick.isShow) onOpenShow(pick.key) else onOpenMovie(pick.key) }
    val play = { item: Resume -> context.startActivity(viewModel.playIntent(item)) }

    if (library.movies.isEmpty() && library.shows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        resume.firstOrNull()?.let { first -> item { Hero(first) { play(first) } } }
        if (resume.size > 1) {
            item {
                Shelf("Lecture en cours") {
                    items(resume.drop(1), key = { it.file }) { ResumeCard(it) { play(it) } }
                }
            }
        }
        if (fresh.isNotEmpty()) {
            item { Shelf("Nouveautés") { items(fresh, key = { it.key }) { PickPoster(it) { open(it) } } } }
        }
        if (movies.isNotEmpty()) {
            item {
                Shelf(
                    "Sélection films",
                    mode = movieMode,
                    onMode = { movieMode = it; movieSeed = Random.nextLong() },
                    onRefresh = { movieSeed = Random.nextLong() },
                ) { items(movies, key = { it.key }) { PickPoster(it) { open(it) } } }
            }
        }
        if (shows.isNotEmpty()) {
            item {
                Shelf(
                    "Sélection séries",
                    mode = showMode,
                    onMode = { showMode = it; showSeed = Random.nextLong() },
                    onRefresh = { showSeed = Random.nextLong() },
                ) { items(shows, key = { it.key }) { PickPoster(it) { open(it) } } }
            }
        }
    }
}

enum class Selection(val label: String) { ForYou("Pour toi"), Random("Au hasard") }

/** The latest thing played, large, with its backdrop. */
@Composable
private fun Hero(item: Resume, onPlay: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onPlay),
        contentAlignment = Alignment.BottomStart,
    ) {
        val image = item.movie?.backdrop ?: item.show?.backdrop ?: item.episode?.still
        if (image != null) {
            AsyncImage(
                model = Tmdb.image(image, "w1280"),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (item.progress != null) "Reprendre" else "À suivre").uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
            Text(item.movie?.title ?: item.show!!.title, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 1)
            item.progress?.let {
                LinearProgressIndicator(progress = { it.fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPlay) { Text(if (item.progress != null) "▶  Reprendre" else "▶  Lecture") }
                Text(describe(item), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ResumeCard(item: Resume, onClick: () -> Unit) {
    Column(modifier = Modifier.width(236.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.BottomStart,
        ) {
            val image = item.episode?.still ?: item.movie?.backdrop ?: item.show?.backdrop
            if (image != null) {
                AsyncImage(
                    model = Tmdb.image(image, "w500"),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            item.progress?.let {
                LinearProgressIndicator(progress = { it.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp), drawStopIndicator = {})
            }
        }
        Text(item.movie?.title ?: item.show!!.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(describe(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** "S02E03 · il reste 14 min", "À suivre : S02E04 · Titre". */
private fun describe(item: Resume): String {
    val code = item.episode?.let { "S%02dE%02d".format(it.season, it.number) }
    val remaining = item.progress?.let { "il reste ${formatRuntime((it.remaining / 60).toInt().coerceAtLeast(1))}" }
    return when {
        remaining != null -> listOfNotNull(code, remaining).joinToString(" · ")
        else -> "À suivre : " + listOfNotNull(code, item.episode?.title).joinToString(" · ")
    }
}

@Composable
private fun Shelf(
    title: String,
    mode: Selection? = null,
    onMode: (Selection) -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (mode != null) {
                Selection.entries.forEach { entry ->
                    FilterChip(selected = entry == mode, onClick = { onMode(entry) }, label = { Text(entry.label) })
                }
            }
            onRefresh?.let { FilterChip(selected = false, onClick = it, label = { Text("↻") }) }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun PickPoster(pick: Pick, onClick: () -> Unit) {
    Column(modifier = Modifier.width(124.dp).clickable(onClick = onClick)) {
        Poster(pick.poster, pick.title, modifier = Modifier.fillMaxWidth())
        Text(
            pick.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
