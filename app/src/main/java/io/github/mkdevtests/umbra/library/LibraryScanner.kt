package io.github.mkdevtests.umbra.library

import android.util.Log
import io.github.mkdevtests.umbra.browse.isVideo
import io.github.mkdevtests.umbra.browse.naturalCompare
import io.github.mkdevtests.umbra.browse.subtitlesFor
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.nas.SmbNas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Folders that never hold library videos: NAS housekeeping, disc structures, bonus material. */
private val SKIPPED_FOLDERS = setOf(
    "@eadir", "#recycle", "\$recycle.bin", "system volume information", "lost+found",
    "video_ts", "audio_ts", "bdmv", "certificate",
    "extras", "extra", "featurettes", "bonus", "behind the scenes", "deleted scenes",
    "interviews", "trailers", "trailer", "samples", "sample", "making of",
)

/** File names that say nothing about the film: the folder names it instead. */
private val GENERIC_FILE = Regex("""(?i)^(movie|film|video|main|feature|vts_\d+_\d+|title_?\d+)$""")

/** Position in a collection folder: "1 Harry Potter", "1- El Mariachi". */
private val COLLECTION_NUMBER = Regex("""^\d{1,2}\s*[-.]?\s+""")

/** "Jumanji 1": the first film is rarely numbered on TMDB. */
private val FIRST_PART = Regex("""\s+1$""")

/** Deep enough for "Share\Séries\Drame\Show\Saison 1", shallow enough to stop on loops. */
private const val MAX_DEPTH = 8

/**
 * Builds the library like Infuse: walks every selected share, whatever the
 * folder layout (genre folders, loose files, one folder per film), and tells
 * films from episodes by file name. Items already matched in [previous] keep
 * their metadata, so a rescan only costs listings plus lookups for new files.
 */
class LibraryScanner(
    private val smb: SmbNas,
    private val tmdb: Tmdb,
    private val onProgress: (String) -> Unit,
) {
    /** Parallel workers: NAS listings and TMDB calls overlap their latency. */
    private val workers = Semaphore(6)

    /** A video found on the NAS; [folders] are the folder names between the share and the file. */
    private class VideoFile(val entry: NasEntry, val folders: List<String>, val subtitles: List<String>)

    private class EpisodeFile(val video: VideoFile, val show: ParsedName, val season: Int, val episode: Int)

    suspend fun scan(previous: Library, source: String): Library {
        val videos = walk()
        if (videos.isEmpty()) throw IOException("Aucune vidéo trouvée dans les partages choisis.")

        val episodes = mutableListOf<EpisodeFile>()
        val movieFiles = mutableListOf<VideoFile>()
        videos.forEach { video -> episodeOf(video)?.let(episodes::add) ?: movieFiles.add(video) }
        Log.i(TAG, "${videos.size} videos: ${movieFiles.size} films, ${episodes.size} episodes")

        val movies = scanMovies(movieFiles, previous.movies.associateBy(Movie::file))
        val shows = scanShows(episodes, previous.shows)
        return Library(
            version = Library.VERSION,
            source = source,
            movies = movies.sortedWith { a, b -> naturalCompare(a.title, b.title) },
            shows = shows.sortedWith { a, b -> naturalCompare(a.title, b.title) },
            scannedAt = System.currentTimeMillis(),
        )
    }

    // --- Walking the shares ---

    private suspend fun walk(): List<VideoFile> = coroutineScope {
        val found = ConcurrentLinkedQueue<VideoFile>()
        val folders = AtomicInteger()

        fun visit(path: String, names: List<String>) {
            launch(Dispatchers.IO) {
                val entries = workers.withPermit {
                    try {
                        list(path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "cannot list $path", e)
                        emptyList()
                    }
                }
                entries.filter { it.isVideo && !it.isExtra() }.forEach { video ->
                    found += VideoFile(video, names, subtitlesFor(video, entries).map { it.path })
                }
                onProgress("Exploration du NAS : ${folders.incrementAndGet()} dossiers, ${found.size} vidéos")
                if (names.size < MAX_DEPTH) {
                    entries.filter { it.isDirectory && !it.isSkipped() }.forEach { visit(it.path, names + it.name) }
                }
            }
        }

        list("").forEach { share -> visit(share.path, emptyList()) }
        found
    }.toList()

    /** The episode a video is, or null for a film. */
    private fun episodeOf(video: VideoFile): EpisodeFile? {
        val folderSeason = video.folders.lastOrNull()?.let(::parseSeasonFolder)
        val name = parseEpisodeName(video.entry.name, inSeasonFolder = folderSeason != null) ?: return null
        // The title in the file name wins: the folder may be a genre ("Séries\Drame\Show.S01E01.mkv").
        val show = name.show
            ?: video.folders.lastOrNull { parseSeasonFolder(it) == null }?.let(::parseMediaName)
            ?: return null
        return EpisodeFile(video, show, name.season ?: folderSeason ?: 1, name.episode)
    }

    // --- Films ---

    private suspend fun scanMovies(files: List<VideoFile>, known: Map<String, Movie>): List<Movie> {
        val names = files.associateWith(::movieName)
        // One lookup per title, even when the same film is there twice.
        val groups = files.groupBy { file ->
            names.getValue(file).let { it.imdbId ?: it.tmdbId?.let { id -> "tmdb:$id" } ?: "${normalizeTitle(it.title)}|${it.year}" }
        }.values.toList()
        val movies = forEachParallel(groups, "Films") { group ->
            val name = names.getValue(group.first())
            val template = group.firstNotNullOfOrNull { known[it.entry.path]?.takeIf { movie -> movie.tmdbId != null } }
                ?: lookup("film ${group.first().entry.name}") {
                    val id = name.tmdbId
                        ?: name.imdbId?.let { tmdb.movieForImdb(it) }
                        ?: tmdb.findMovie(searchQueries(name), name.year)
                    id?.let { tmdb.movie(it).toMovie(name) }
                }
                ?: Movie(file = "", fileSize = 0, title = name.title, year = name.year)
            group.map { template.copy(file = it.entry.path, fileSize = it.entry.size, subtitles = it.subtitles) }
        }.flatten()
        // Several copies of a film (1080p and 4K, or in two genre folders): keep the biggest.
        return movies
            .groupBy { it.tmdbId?.toString() ?: "${normalizeTitle(it.title)}|${it.year}" }
            .map { (_, copies) -> copies.maxBy { it.fileSize } }
    }

    /** Title from the file name; the folder only when the file name is useless or the folder is "Titre (Année)". */
    private fun movieName(video: VideoFile): ParsedName {
        val fromFile = parseMediaName(video.entry.name)
        val folder = video.folders.lastOrNull() ?: return fromFile
        val generic = GENERIC_FILE.matches(video.entry.name.substringBeforeLast('.')) || fromFile.title.count { it.isLetter() } < 2
        return when {
            generic -> parseMediaName(folder)
            fromFile.year == null && isTitleWithYear(folder) -> parseMediaName(folder)
            else -> fromFile
        }
    }

    private fun TmdbMovie.toMovie(name: ParsedName) = Movie(
        file = "",
        fileSize = 0,
        tmdbId = id,
        title = title,
        originalTitle = originalTitle,
        year = releaseDate.year() ?: name.year,
        overview = overview,
        tagline = tagline?.ifBlank { null },
        poster = posterPath,
        backdrop = backdropPath,
        runtime = runtime?.takeIf { it > 0 },
        genres = genres.map { it.name },
        rating = voteAverage?.takeIf { it > 0 },
    )

    // --- Shows ---

    private suspend fun scanShows(episodes: List<EpisodeFile>, previous: List<Show>): List<Show> {
        val knownShowOf = previous.flatMap { show -> show.seasons.flatMap { it.episodes }.map { it.file to show } }.toMap()
        val knownEpisodes = previous.flatMap { show -> show.seasons.flatMap { it.episodes } }.associateBy { it.file }
        val previousByKey = previous.associateBy { it.key }

        // 1. Which show each title is.
        val groups = episodes.groupBy { normalizeTitle(it.show.title) }.values.toList()
        val identified = forEachParallel(groups, "Séries") { group ->
            val known = group.firstNotNullOfOrNull { knownShowOf[it.video.entry.path]?.takeIf { show -> show.tmdbId != null } }
            val name = group.groupingBy { it.show }.eachCount().maxBy { it.value }.key
            (known?.copy(seasons = emptyList()) ?: matchShow(name)) to group
        }
        // "Show" and "Show (2019)" may be the same TMDB show.
        val merged = identified.groupBy { it.first.key }.map { (_, parts) -> parts.first().first to parts.flatMap { it.second } }

        // 2. Seasons, with TMDB episode details for files not known yet.
        return forEachParallel(merged, "Épisodes") { (show, files) ->
            val unique = files.groupBy { it.season to it.episode }.map { (_, copies) -> copies.maxBy { it.video.entry.size } }
            val seasons = unique.groupBy { it.season }.map { (number, seasonFiles) ->
                val episodesOfSeason = seasonFiles.sortedBy { it.episode }.map { file ->
                    val video = file.video
                    knownEpisodes[video.entry.path]
                        ?.takeIf { it.hasMetadata && it.season == number && it.number == file.episode }
                        ?.copy(fileSize = video.entry.size, subtitles = video.subtitles)
                        ?: Episode(number, file.episode, video.entry.path, video.entry.size, video.subtitles)
                }
                val knownSeason = previousByKey[show.key]?.seasons?.firstOrNull { it.number == number }
                val season = Season(number, knownSeason?.name, knownSeason?.poster, episodesOfSeason)
                if (show.tmdbId != null && episodesOfSeason.any { !it.hasMetadata }) {
                    lookup("saison $number de ${show.title}") { withSeasonDetails(show.tmdbId, season) } ?: season
                } else {
                    season
                }
            }
            show.copy(seasons = seasons.sortedBy { it.number })
        }
    }

    private suspend fun matchShow(name: ParsedName): Show {
        val unmatched = Show(key = "title:${normalizeTitle(name.title)}", title = name.title, year = name.year)
        val match = lookup("série ${name.title}") {
            tmdb.findShow(searchQueries(name), name.year)?.let { tmdb.show(it) }
        } ?: return unmatched
        return Show(
            key = "tmdb:${match.id}",
            tmdbId = match.id,
            title = match.name,
            originalTitle = match.originalName,
            year = match.firstAirDate.year() ?: name.year,
            overview = match.overview,
            poster = match.posterPath,
            backdrop = match.backdropPath,
            genres = match.genres.map { it.name },
            rating = match.voteAverage?.takeIf { it > 0 },
        )
    }

    private suspend fun withSeasonDetails(showId: Int, season: Season): Season {
        val details = tmdb.season(showId, season.number)
        val byNumber = details.episodes.associateBy { it.number }
        return season.copy(
            name = details.name,
            poster = details.posterPath,
            episodes = season.episodes.map { episode ->
                val info = byNumber[episode.number] ?: return@map episode
                episode.copy(
                    title = info.name ?: "Épisode ${episode.number}",
                    overview = info.overview?.ifBlank { null },
                    still = info.stillPath,
                    airDate = info.airDate,
                    runtime = info.runtime,
                )
            },
        )
    }

    // --- Helpers ---

    /**
     * TMDB queries for a parsed name, most precise first: with the subtitle
     * ("The Hobbit The Desolation of Smaug"), the title, the title without a
     * collection number ("1 Harry Potter…", "Jumanji 1"), then each part of a
     * "French title - original title" name.
     */
    private fun searchQueries(name: ParsedName): List<String> = buildList {
        name.subtitle?.let { add("${name.title} $it") }
        add(name.title)
        val unnumbered = name.title.replace(COLLECTION_NUMBER, "").replace(FIRST_PART, "").trim()
        if (unnumbered.count { it.isLetter() } >= 2) add(unnumbered)
        if (" - " in name.title) addAll(name.title.split(" - ").map { it.trim() }.filter { part -> part.count { it.isLetter() } >= 2 })
    }

    private fun list(path: String) = smb.list(path)

    private suspend fun <T, R : Any> forEachParallel(items: List<T>, label: String, block: suspend (T) -> R?): List<R> {
        val done = AtomicInteger()
        onProgress("$label : 0 / ${items.size}")
        return coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    workers.withPermit {
                        try {
                            block(item)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "skipped $item", e)
                            null
                        } finally {
                            onProgress("$label : ${done.incrementAndGet()} / ${items.size}")
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** TMDB call whose failure leaves the item unmatched; the next scan retries it. */
    private suspend fun <T> lookup(what: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "TMDB lookup failed for $what", e)
        null
    }

    private fun NasEntry.isExtra() = name.contains("sample", ignoreCase = true) || name.contains("trailer", ignoreCase = true)

    /** "Bonus [Edition 25eme Anniversaire]" counts as "bonus". */
    private fun NasEntry.isSkipped() =
        name.startsWith('.') || name.lowercase().substringBefore('[').substringBefore('(').trim() in SKIPPED_FOLDERS

    private fun String?.year() = this?.take(4)?.toIntOrNull()

    private companion object {
        const val TAG = "LibraryScanner"
    }
}
