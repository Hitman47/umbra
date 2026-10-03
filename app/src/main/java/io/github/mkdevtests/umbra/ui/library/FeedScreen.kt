package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.history.seenOf
import io.github.mkdevtests.umbra.home.Pick
import io.github.mkdevtests.umbra.home.Resume
import io.github.mkdevtests.umbra.home.atRandom
import io.github.mkdevtests.umbra.home.continueWatching
import io.github.mkdevtests.umbra.home.forYou
import io.github.mkdevtests.umbra.home.newest
import io.github.mkdevtests.umbra.home.nextUp
import io.github.mkdevtests.umbra.home.regularEpisodes
import io.github.mkdevtests.umbra.home.taste
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.sagasOf
import io.github.mkdevtests.umbra.library.Tmdb
import io.github.mkdevtests.umbra.ui.theme.GlassButton
import io.github.mkdevtests.umbra.ui.theme.GlowButton
import io.github.mkdevtests.umbra.ui.theme.Night
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.SectionHeader
import kotlinx.coroutines.delay
import kotlin.random.Random

/** One page of the home banner: something to resume, a new arrival, a title worth watching. */
private class Featured(
    val key: String,
    val label: String,
    val title: String,
    val backdrop: String,
    val meta: String,
    val progress: Float?,
    val playLabel: String,
    val play: () -> Unit,
    val open: () -> Unit,
)

/**
 * The "Accueil" tab: a banner of what to resume and what's new, then rows:
 * playback in progress, new arrivals, selections and the library's main genres.
 */
@Composable
fun FeedScreen(
    library: Library,
    scanning: Boolean,
    history: Map<String, Progress>,
    viewModel: LibraryViewModel,
    wide: Boolean,
    emptyText: String,
    links: TitleLinks,
    shortcuts: List<String>,
    onOpenShortcut: (String) -> Unit,
    onLongPress: (TitleTarget) -> Unit,
) {
    val onOpenMovie = links.onOpenMovie
    val onOpenShow = links.onOpenShow
    val context = LocalContext.current
    // A scan publishes the library many times: keep the rows still until it ends,
    // or a poster moves under the finger.
    var shown by remember { mutableStateOf(library) }
    LaunchedEffect(library, scanning) {
        if (!scanning || (shown.movies.isEmpty() && shown.shows.isEmpty())) shown = library
    }
    val library = shown
    val movieByFile = remember(library) { library.movies.associateBy { it.file } }
    val showByKey = remember(library) { library.shows.associateBy { it.key } }
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
    // The library's main genres, each a row of its best rated titles.
    val genres = remember(library) {
        val titles = library.movies.map { Triple(it.genres, it.rating, Pick(it.file, it.title, it.poster, isShow = false)) } +
            library.shows.map { Triple(it.genres, it.rating, Pick(it.key, it.title, it.poster, isShow = true)) }
        titles.flatMap { it.first }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(GENRE_ROWS).map { (genre, _) ->
            genre to titles.filter { genre in it.first }.sortedByDescending { it.second ?: 0.0 }.take(20).map { it.third }
        }.filter { it.second.size >= 4 }
    }
    val sagas = remember(library) { sagasOf(library) }
    val open = { pick: Pick -> if (pick.isShow) onOpenShow(pick.key) else onOpenMovie(pick.key) }
    val press = { pick: Pick -> onLongPress(TitleTarget(pick.key, pick.isShow)) }
    val play = { item: Resume -> context.startActivity(viewModel.playIntent(item)) }

    val featured = remember(resume, fresh, library) {
        val resumed = resume.take(3).mapNotNull { item ->
            val backdrop = item.movie?.backdrop ?: item.show?.backdrop ?: return@mapNotNull null
            Featured(
                key = item.file,
                label = if (item.progress != null) "Reprendre" else "À suivre",
                title = item.movie?.title ?: item.show!!.title,
                backdrop = backdrop,
                meta = describe(item),
                progress = item.progress?.fraction,
                playLabel = if (item.progress != null) "Reprendre" else "Lecture",
                play = { play(item) },
                open = { item.movie?.let { onOpenMovie(it.file) } ?: onOpenShow(item.show!!.key) },
            )
        }
        val arrivals = fresh.mapNotNull { pick ->
            val movie = if (pick.isShow) null else movieByFile[pick.key]
            val show = if (pick.isShow) showByKey[pick.key] else null
            val backdrop = movie?.backdrop ?: show?.backdrop ?: return@mapNotNull null
            if (resume.any { it.movie?.file == movie?.file && movie != null || it.show?.key == show?.key && show != null }) return@mapNotNull null
            val genres = (movie?.genres ?: show?.genres).orEmpty().take(2)
            Featured(
                key = pick.key,
                label = "Nouveau",
                title = pick.title,
                backdrop = backdrop,
                meta = listOfNotNull((movie?.year ?: show?.year)?.toString(), formatRuntime(movie?.runtime), formatRating(movie?.rating ?: show?.rating))
                    .plus(genres).joinToString("  ·  "),
                progress = null,
                playLabel = "Lecture",
                play = {
                    if (movie != null) {
                        context.startActivity(viewModel.playIntent(movie))
                    } else {
                        val episode = nextUp(show!!, history)?.episode ?: show.regularEpisodes().firstOrNull()
                        if (episode != null) context.startActivity(viewModel.playIntent(show, episode)) else onOpenShow(show.key)
                    }
                },
                open = { open(pick) },
            )
        }
        (resumed + arrivals).take(HERO_PAGES)
    }

    if (library.movies.isEmpty() && library.shows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    fun posterOf(pick: Pick): PosterItem {
        val movie = if (pick.isShow) null else movieByFile[pick.key]
        val show = if (pick.isShow) showByKey[pick.key] else null
        val badge = movie?.let { seenOf(it, history).badge } ?: show?.let { seenOf(it, history).badge }
        return PosterItem(pick.key, pick.title, movie?.year ?: show?.year, pick.poster, badge = badge)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (featured.isNotEmpty()) item { HeroPager(featured, wide) }
        if (shortcuts.isNotEmpty()) item { Shortcuts(shortcuts, onOpenShortcut) }
        if (resume.isNotEmpty()) {
            item {
                Shelf("Lecture en cours") {
                    items(resume, key = { it.file }) { item ->
                        ResumeCard(
                            item, wide,
                            onOpen = { item.movie?.let { onOpenMovie(it.file) } ?: onOpenShow(item.show!!.key) },
                            onPlay = { play(item) },
                            onLongPress = {
                                onLongPress(item.movie?.let { TitleTarget(it.file) } ?: TitleTarget(item.show!!.key, isShow = true, episode = item.episode?.file))
                            },
                        )
                    }
                }
            }
        }
        if (fresh.isNotEmpty()) {
            item { Shelf("Ajouts récents") { items(fresh, key = { it.key }) { PosterCard(posterOf(it), { open(it) }, Modifier.width(POSTER), onLongClick = { press(it) }) } } }
        }
        if (movies.isNotEmpty()) {
            item {
                Shelf(
                    "Sélection films",
                    mode = movieMode,
                    onMode = { movieMode = it; movieSeed = Random.nextLong() },
                    onRefresh = { movieSeed = Random.nextLong() },
                ) { items(movies, key = { it.key }) { PosterCard(posterOf(it), { open(it) }, Modifier.width(POSTER), onLongClick = { press(it) }) } }
            }
        }
        if (shows.isNotEmpty()) {
            item {
                Shelf(
                    "Sélection séries",
                    mode = showMode,
                    onMode = { showMode = it; showSeed = Random.nextLong() },
                    onRefresh = { showSeed = Random.nextLong() },
                ) { items(shows, key = { it.key }) { PosterCard(posterOf(it), { open(it) }, Modifier.width(POSTER), onLongClick = { press(it) }) } }
            }
        }
        if (sagas.isNotEmpty()) {
            item {
                Shelf("Sagas") {
                    items(sagas, key = { it.id }) { saga ->
                        PosterCard(
                            sagaItem(saga, history), { links.onOpenSaga(saga.id) }, Modifier.width(POSTER),
                            onLongClick = { onLongPress(TitleTarget("saga:${saga.id}")) },
                        )
                    }
                }
            }
        }
        genres.forEach { (genre, picks) ->
            item(key = "genre:$genre") {
                Shelf(genre) { items(picks, key = { it.key }) { PosterCard(posterOf(it), { open(it) }, Modifier.width(POSTER), onLongClick = { press(it) }) } }
            }
        }
    }
}

enum class Selection(val label: String) { ForYou("Pour toi"), Random("Au hasard") }

private val POSTER = 132.dp
private const val HERO_PAGES = 6
private const val GENRE_ROWS = 4

/** The banner: one large backdrop at a time, turning by itself every few seconds. */
@Composable
private fun HeroPager(items: List<Featured>, wide: Boolean) {
    val pager = rememberPagerState(pageCount = { items.size })
    LaunchedEffect(pager, items.size) {
        while (items.size > 1) {
            delay(8_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Box {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().height(if (wide) 420.dp else 460.dp),
            key = { items[it].key },
        ) { page -> HeroPage(items[page], wide) }
        if (items.size > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.indices.forEach { i ->
                    val current = i == pager.currentPage
                    Box(
                        modifier = Modifier
                            .size(width = if (current) 18.dp else 6.dp, height = 6.dp)
                            .clip(CircleShape)
                            .background(if (current) Night.Glow else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.3f)))),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPage(item: Featured, wide: Boolean) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier = Modifier.fillMaxSize().clickable(onClick = item.open)) {
        AsyncImage(
            model = Tmdb.image(item.backdrop, "w1280"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to background.copy(alpha = 0.25f), 0.35f to Color.Transparent, 0.65f to background.copy(alpha = 0.7f), 1f to background),
            ),
        )
        if (wide) {
            Box(modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to background.copy(alpha = 0.85f), 0.55f to Color.Transparent)))
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 30.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(item.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                item.title,
                style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.meta.isNotEmpty()) {
                Text(item.meta, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    trackColor = Color.White.copy(alpha = 0.2f),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
            }
            Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlowButton(item.playLabel, onClick = item.play)
                GlassButton("Infos", onClick = item.open, icon = NyxaraIcons.Info)
            }
        }
    }
}

/** A film or episode under way: its picture, how far it went, what's left. A touch opens its page, ▶ plays it. */
@Composable
private fun ResumeCard(item: Resume, wide: Boolean, onOpen: () -> Unit, onPlay: () -> Unit, onLongPress: () -> Unit) {
    Column(
        modifier = Modifier.width(if (wide) 300.dp else 260.dp).combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Night.Glow)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) { Icon(NyxaraIcons.Play, contentDescription = "Reprendre", tint = Color.White) }
            Text(
                item.movie?.title ?: item.show!!.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
            item.progress?.let {
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f)))
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(it.fraction).height(3.dp).background(Night.Glow))
            }
        }
        Text(describe(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The folders chosen as shortcuts ("Films", "Anime"…), each opening its titles. */
@Composable
private fun Shortcuts(roots: List<String>, onOpen: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(roots, key = { it }) { root ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpen(root) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(shortcutIcon(root), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text(root, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
    }
}

/** An icon guessed from the folder's name. */
internal fun shortcutIcon(root: String) = root.lowercase().let { name ->
    when {
        "anim" in name -> NyxaraIcons.Sparkle
        "série" in name || "serie" in name || "show" in name || "tv" in name -> NyxaraIcons.Tv
        "film" in name || "movie" in name || "ciné" in name -> NyxaraIcons.Movie
        "spectacle" in name || "concert" in name || "humour" in name -> NyxaraIcons.Mic
        "doc" in name -> NyxaraIcons.Info
        else -> NyxaraIcons.Folder
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
        SectionHeader(title) {
            if (mode != null) {
                Selection.entries.forEach { entry ->
                    FilterChip(selected = entry == mode, onClick = { onMode(entry) }, label = { Text(entry.label) })
                }
            }
            onRefresh?.let {
                IconButton(onClick = it) { Icon(NyxaraIcons.Shuffle, contentDescription = "Autre sélection") }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}
