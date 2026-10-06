package io.github.mkdevtests.umbra.ui.library

import io.github.mkdevtests.umbra.ui.theme.scaled
import io.github.mkdevtests.umbra.ui.theme.LocalCardScale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
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
import io.github.mkdevtests.umbra.library.Version
import io.github.mkdevtests.umbra.library.describeVersion
import io.github.mkdevtests.umbra.download.Download
import io.github.mkdevtests.umbra.download.DownloadState
import io.github.mkdevtests.umbra.library.LinkRow
import io.github.mkdevtests.umbra.library.characterRow
import io.github.mkdevtests.umbra.library.directorRow
import io.github.mkdevtests.umbra.library.groupLabel
import io.github.mkdevtests.umbra.library.linkables
import io.github.mkdevtests.umbra.library.versions
import io.github.mkdevtests.umbra.library.versionsOf
import io.github.mkdevtests.umbra.media.MediaInfo
import io.github.mkdevtests.umbra.media.audioLanguages
import io.github.mkdevtests.umbra.media.badges
import io.github.mkdevtests.umbra.media.compactLine
import io.github.mkdevtests.umbra.media.detailLine
import io.github.mkdevtests.umbra.media.resolutionLabel
import io.github.mkdevtests.umbra.media.subtitleLanguages
import io.github.mkdevtests.umbra.ui.theme.GlassButton
import io.github.mkdevtests.umbra.ui.theme.GlassIconButton
import io.github.mkdevtests.umbra.ui.theme.GlowButton
import io.github.mkdevtests.umbra.ui.theme.NyxaraIcons
import io.github.mkdevtests.umbra.ui.theme.Tag
import io.github.mkdevtests.umbra.ui.theme.focusRing
import java.util.Locale

/** Where a page leads: another title of the library, or the search for a person. */
class DetailLinks(
    val onOpenMovie: (String) -> Unit,
    val onOpenShow: (String) -> Unit,
    val onOpenSaga: (Int) -> Unit,
    val onPerson: (String) -> Unit,
    val onOpenUniverse: (String) -> Unit = {},
    /** A title the library doesn't have, by its TMDB id: its page. */
    val onOpenRemote: (Int, Boolean) -> Unit = { _, _ -> },
) {
    val titleLinks get() = TitleLinks(onOpenMovie, onOpenShow, onOpenSaga, onOpenUniverse)
}

@Composable
fun MovieDetailScreen(movie: Movie, viewModel: LibraryViewModel, links: DetailLinks, onBack: () -> Unit) {
    val context = LocalContext.current
    var extras by remember(movie.file) { mutableStateOf<Extras?>(null) }
    LaunchedEffect(movie.file) { extras = viewModel.extras(movie) }
    val versions = remember(movie) { movie.versions }
    var version by remember(movie) { mutableStateOf(viewModel.versionFor(movie.file, versions)) }
    val history by viewModel.history.collectAsState()
    val progress = history[movie.file]
    val hidden by viewModel.hidden.collectAsState()
    val isHidden = movie.hideKey in hidden
    val infos by viewModel.mediaInfo.collectAsState()
    LaunchedEffect(versions) { viewModel.requestMediaInfo(versions.map { it.file to it.size }) }
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
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlowButton(
                            "Reprendre · il reste ${formatRuntime((progress.remaining / 60).toInt().coerceAtLeast(1))}",
                            onClick = { context.startActivity(viewModel.playIntent(movie, version = version)) },
                        )
                        GlassButton("Depuis le début", onClick = { context.startActivity(viewModel.playIntent(movie, fromStart = true, version = version)) })
                    }
                } else {
                    GlowButton(
                        if (progress?.watched == true) "Revoir" else "Lecture",
                        onClick = { context.startActivity(viewModel.playIntent(movie, version = version)) },
                    )
                }
                MarkButton(progress?.watched == true) { viewModel.markWatched(movie, progress?.watched != true) }
                DownloadButton(viewModel, movie.file) { viewModel.download(movie) }
                HideButton(isHidden) { viewModel.setHidden(movie, !isHidden) }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                infos[version.file]?.let { MediaBlock(it) }
                movie.tagline?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic) }
                movie.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (extras == null) Credits("Réalisation", movie.directors, movie.cast)
            }
        }
        if (versions.size > 1) {
            item {
                VersionList(versions, version, viewModel::sourceLabel, infos = infos) {
                    version = it
                    viewModel.chooseVersion(movie.file, it)
                }
            }
        }
        item { ExtrasRows(extras, links, localRows(viewModel, movie = movie.file), movie.sagaId) }
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) { FileInfo(version.file, version.size) }
        }
    }
}

@Composable
fun ShowDetailScreen(show: Show, viewModel: LibraryViewModel, links: DetailLinks, onBack: () -> Unit, onFixMatch: () -> Unit) {
    val context = LocalContext.current
    var extras by remember(show.key) { mutableStateOf<Extras?>(null) }
    LaunchedEffect(show.key) { extras = viewModel.extras(show) }
    // The season under way first, else the first regular one.
    var selected by rememberSaveable(show.key) {
        mutableIntStateOf(
            nextUp(show, viewModel.history.value)?.episode?.season
                ?: show.seasons.firstOrNull { it.number > 0 }?.number ?: show.seasons.firstOrNull()?.number ?: 1,
        )
    }
    var picking by remember(show.key) { mutableStateOf<Episode?>(null) }
    var pressed by remember(show.key) { mutableStateOf<Episode?>(null) }
    val downloads by viewModel.downloads.collectAsState()
    val season = show.seasons.firstOrNull { it.number == selected }
    val history by viewModel.history.collectAsState()
    val hidden by viewModel.hidden.collectAsState()
    val isHidden = show.hideKey in hidden
    val infos by viewModel.mediaInfo.collectAsState()
    LaunchedEffect(season) { viewModel.requestMediaInfo(season?.episodes.orEmpty().map { it.file to it.fileSize }) }

    val scan by viewModel.scan.collectAsState()
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
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (next != null) {
                        val code = "S%02dE%02d".format(next.season, next.number)
                        GlowButton(if (resume?.progress != null) "Reprendre $code" else "Lecture $code", onClick = { context.startActivity(viewModel.playIntent(show, next)) })
                    }
                    GlassButton("Corriger", onClick = onFixMatch, icon = NyxaraIcons.Edit)
                }
                val seen = io.github.mkdevtests.umbra.history.seenOf(show, history)
                MarkButton(seen.all, all = true) { viewModel.markWatched(show, !seen.all) }
                HideButton(isHidden) { viewModel.setHidden(show, !isHidden) }
            }
        }
        // A correction being applied, or a scan: the page changes when it ends.
        if (scan.running || scan.error != null) {
            item {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (scan.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    (scan.progress ?: scan.error)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (scan.running) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                show.overview?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (extras == null) Credits("Création", show.directors, show.cast)
                // Where its files are, and which TMDB entry it is: two shows may share a title.
                CreditLine(
                    "Sur le NAS",
                    (show.groups.map(::groupLabel).map { it.replace("\\", " › ") } + listOfNotNull(show.tmdbId?.let { "TMDB $it" })).joinToString(" · "),
                )
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
        // The season as TMDB tells it: poster, year, episodes, rating, text.
        season?.let { shown -> show.seasonInfo.firstOrNull { it.number == shown.number } }?.let { info ->
            item(key = "season-info-${info.number}") { SeasonHeader(season!!, info) }
        }
        season?.takeIf { it.episodes.isNotEmpty() }?.let { shown ->
            item {
                val allSeen = shown.episodes.all { history[it.file]?.watched == true }
                Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = { viewModel.markWatched(show, !allSeen, shown.episodes) }) {
                        Icon(if (allSeen) NyxaraIcons.Close else NyxaraIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (allSeen) "  Marquer la saison non vue" else "  Marquer la saison vue")
                    }
                    TextButton(onClick = { viewModel.download(show, shown.episodes) }) {
                        Icon(NyxaraIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Télécharger la saison")
                    }
                }
            }
        }
        items(season?.episodes.orEmpty(), key = { "${it.season}-${it.number}" }) { episode ->
            val versions = show.versionsOf(episode)
            EpisodeRow(episode, history[episode.file], versions.size, infos[episode.file], downloads.firstOrNull { it.key == episode.file }, onLongClick = { pressed = episode }) {
                // Several files of this episode: which one, first.
                if (versions.size > 1) picking = episode else context.startActivity(viewModel.playIntent(show, episode))
            }
        }
        item { Column(modifier = Modifier.padding(bottom = 32.dp)) { ExtrasRows(extras, links, localRows(viewModel, show = show.key)) } }
    }

    pressed?.let { episode -> EpisodeMenu(show, episode, history, viewModel, onOpen = null) { pressed = null } }

    picking?.let { episode ->
        val versions = show.versionsOf(episode)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("S%02dE%02d · quelle version ?".format(episode.season, episode.number)) },
            text = {
                LaunchedEffect(versions) { viewModel.requestMediaInfo(versions.map { it.file to it.size }) }
                VersionList(versions, viewModel.versionFor(episode.file, versions), viewModel::sourceLabel, padding = 0.dp, infos = infos) { chosen ->
                    picking = null
                    viewModel.chooseVersion(episode.file, chosen)
                    context.startActivity(viewModel.playIntent(show, episode, version = chosen))
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Annuler") } },
        )
    }
}

/** The files of a title, with what their names say of them; the chosen one highlighted. */
@Composable
private fun VersionList(
    versions: List<Version>,
    selected: Version,
    sourceLabel: (String) -> String?,
    padding: androidx.compose.ui.unit.Dp = 24.dp,
    infos: Map<String, MediaInfo> = emptyMap(),
    onSelect: (Version) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = padding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (padding > 0.dp) Text("Versions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        versions.forEach { version ->
            val info = describeVersion(version.file, version.size)
            val chosen = version == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(version) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = chosen, onClick = null)
                Column(modifier = Modifier.weight(1f)) {
                    val media = infos[version.file]
                    Text(
                        media?.let { listOfNotNull(resolutionLabel(it.video?.width, it.video?.height), it.video?.hdr).joinToString(" ") }?.ifEmpty { null } ?: info.quality,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    media?.let { Text(it.detailLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Text(
                        listOfNotNull(info.details, sourceLabel(version.file)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        version.file.substringAfterLast('\\'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Cast and crew with their photos, the saga, the library's related titles and TMDB's recommendations. */
@Composable
private fun ExtrasRows(extras: Extras?, links: DetailLinks, local: List<LinkRow>, sagaId: Int? = null) {
    Column {
        if (extras != null) {
            PeopleRow("Distribution", extras.cast, links.onPerson)
            PeopleRow("Équipe", extras.crew, links.onPerson)
            RelatedRow(extras.saga ?: "Saga", extras.sagaParts, links, onMore = sagaId?.let { { links.onOpenSaga(it) } })
        }
        // The library's titles with the same characters, by the same director: offline too.
        local.forEach { RelatedRow(it.title, it.items, links) }
        if (extras != null) {
            val shown = local.flatMapTo(HashSet()) { row -> row.items.mapNotNull { it.movie ?: it.show } }
            RelatedRow("Dans la bibliothèque", extras.linked.filter { (it.movie ?: it.show) !in shown }, links)
            RelatedRow("Titres similaires", extras.recommended, links)
        }
    }
}

/** "Aussi avec Batman" and "Du même réalisateur" for the film [movie] or the show [show], from the library alone. */
@Composable
private fun localRows(viewModel: LibraryViewModel, movie: String? = null, show: String? = null): List<LinkRow> {
    val library by viewModel.library.collectAsState()
    return remember(library, movie, show) {
        val all = library.linkables()
        val self = all.firstOrNull { (movie != null && it.movie == movie) || (show != null && it.show == show) } ?: return@remember emptyList()
        val characters = characterRow(self, all)
        val director = directorRow(self, all, characters?.items.orEmpty().mapNotNullTo(HashSet()) { it.movie ?: it.show })
        listOfNotNull(characters, director)
    }
}

@Composable
private fun RowTitle(title: String, onMore: (() -> Unit)? = null) {
    io.github.mkdevtests.umbra.ui.theme.SectionHeader(title, modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp)) {
        onMore?.let { TextButton(onClick = it) { Text("Tout voir ›") } }
    }
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
private fun RelatedRow(title: String, items: List<Related>, links: DetailLinks, onMore: (() -> Unit)? = null) {
    if (items.isEmpty()) return
    RowTitle(title, onMore)
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items) { item ->
            val open = item.movie?.let { { links.onOpenMovie(it) } } ?: item.show?.let { { links.onOpenShow(it) } }
                ?: item.tmdbId?.let { id -> { links.onOpenRemote(id, item.isShow) } }
            Column(
                modifier = Modifier
                    .width(110.dp * LocalCardScale.current)
                    .alpha(if (item.owned) 1f else 0.45f)
                    .then(if (open != null) Modifier.clickable(onClick = open) else Modifier),
            ) {
                Poster(item.poster, item.title, modifier = Modifier.fillMaxWidth(), size = "w185")
                Text(item.title, style = MaterialTheme.typography.bodySmall.scaled(LocalCardScale.current), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(
                    listOfNotNull(item.year?.toString(), "absent".takeIf { !item.owned }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.scaled(LocalCardScale.current),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the file holds: badges ("4K", "HDR10", "HEVC", "E-AC3 5.1"), then its audio and subtitle languages. */
@Composable
private fun MediaBlock(info: MediaInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val badges = info.badges()
        if (badges.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { badges.forEach { Tag(it) } }
        val audio = info.audioLanguages()
        val subtitles = info.subtitleLanguages()
        if (audio.isNotEmpty()) CreditLine("Audio", audio.joinToString(", ") { languageName(it) })
        CreditLine("Sous-titres", if (subtitles.isEmpty()) "aucun dans le fichier" else subtitles.joinToString(", ") { languageName(it) })
    }
}

/** "FR" → "Français", "FR forcés" → "Français (forcés)". */
private fun languageName(label: String): String {
    val code = label.substringBefore(' ')
    val name = when (code) {
        "VFF" -> "Français (VFF)"
        "VFQ" -> "Français (VFQ)"
        else -> Locale.forLanguageTag(code.lowercase()).getDisplayLanguage(Locale.FRENCH).replaceFirstChar { it.uppercase() }.ifEmpty { code }
    }
    return if (label.endsWith(" forcés")) "$name (forcés)" else name
}

/** Marks the title (or, [all], every episode) watched, or not watched any more. */
@Composable
private fun MarkButton(watched: Boolean, all: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(if (watched) NyxaraIcons.Close else NyxaraIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            when {
                all && watched -> "  Tout marquer non vu"
                all -> "  Tout marquer vu"
                watched -> "  Marquer comme non vu"
                else -> "  Marquer comme vu"
            },
        )
    }
}

/** Copy to the device: offered, under way (cancel), done (delete) or failed (try again). */
@Composable
private fun DownloadButton(viewModel: LibraryViewModel, key: String, onDownload: () -> Unit) {
    val downloads by viewModel.downloads.collectAsState()
    val download = downloads.firstOrNull { it.key == key }
    TextButton(
        onClick = {
            when (download?.state) {
                null -> onDownload()
                DownloadState.Failed -> viewModel.retryDownload(key)
                else -> viewModel.removeDownload(key)
            }
        },
    ) {
        Icon(if (download?.state == DownloadState.Done) NyxaraIcons.Close else NyxaraIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            "  " + when (download?.state) {
                null -> "Télécharger"
                DownloadState.Done -> "Sur la tablette · supprimer"
                DownloadState.Failed -> "Échec · réessayer"
                else -> "${downloadLabel(download)} · annuler"
            },
        )
    }
}

/** "Sur la tablette", "Téléchargement 45 %", "En attente". */
internal fun downloadLabel(download: Download): String = when (download.state) {
    DownloadState.Done -> "⬇ Sur la tablette"
    DownloadState.Running -> "⬇ Téléchargement ${(download.fraction * 100).toInt()} %"
    DownloadState.Queued -> "⬇ En attente"
    DownloadState.Failed -> "⬇ Échec : ${download.error ?: "?"}"
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
private fun EpisodeRow(episode: Episode, progress: Progress?, versions: Int, media: MediaInfo?, download: Download?, onLongClick: () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 24.dp, vertical = 10.dp),
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
            val meta = listOfNotNull(formatRuntime(episode.runtime), episode.airDate?.let(::formatDate), "$versions versions".takeIf { versions > 1 })
            if (meta.isNotEmpty()) {
                Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            download?.let { Text(downloadLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
            media?.compactLine()?.ifEmpty { null }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** What TMDB says of a season: its poster, its year and size, its rating, its text (opened on a touch). */
@Composable
private fun SeasonHeader(season: io.github.mkdevtests.umbra.library.Season, info: io.github.mkdevtests.umbra.library.SeasonInfo) {
    var open by remember(info.number) { mutableStateOf(false) }
    val meta = listOfNotNull(
        info.airDate?.take(4),
        "${info.episodes.takeIf { it > 0 } ?: season.episodes.size} épisodes",
        formatRating(info.rating),
    ).joinToString("  ·  ")
    Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        season.poster?.let { Poster(it, season.name ?: "", modifier = Modifier.width(72.dp), size = "w185") }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(season.name ?: if (season.number == 0) "Épisodes spéciaux" else "Saison ${season.number}", style = MaterialTheme.typography.titleMedium)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.overview?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (open) Int.MAX_VALUE else 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { open = !open },
                )
            }
        }
    }
}
