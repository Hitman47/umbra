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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private val MOVIE_FOLDERS = setOf("films", "film", "movies", "movie")
private val SHOW_FOLDERS = setOf("series", "serie", "tv", "tv shows", "shows", "anime", "animes", "manga", "mangas")

/**
 * Walks the movie and show folders (shares named "Films", "Séries", "Anime"…
 * or such folders right inside a share) and matches each title on TMDB. Items already matched in [previous] keep their metadata, so
 * a rescan only costs folder listings plus lookups for new titles.
 */
class LibraryScanner(
    private val smb: SmbNas,
    private val tmdb: Tmdb,
    private val onProgress: (String) -> Unit,
) {
    /** Parallel workers: NAS listings and TMDB calls overlap their latency. */
    private val workers = Semaphore(6)

    suspend fun scan(previous: Library, source: String): Library {
        // The shares themselves, plus the folders right inside the other shares ("Vidéos\Films").
        val shares = list("")
        val moviesRoots = mutableListOf<String>()
        val showsRoots = mutableListOf<String>()
        fun classify(folder: NasEntry): Boolean {
            val name = folder.name.withoutAccents().lowercase().trim()
            when (name) {
                in MOVIE_FOLDERS -> moviesRoots += folder.path
                in SHOW_FOLDERS -> showsRoots += folder.path
                else -> return false
            }
            return true
        }
        shares.filterNot(::classify).forEach { share ->
            runCatching { list(share.path) }.getOrDefault(emptyList()).filter { it.isDirectory }.forEach(::classify)
        }
        if (moviesRoots.isEmpty() && showsRoots.isEmpty()) {
            throw IOException("Aucun partage ou dossier « Films », « Séries » ou « Anime » trouvé sur le NAS.")
        }
        Log.i(TAG, "movies in $moviesRoots, shows in $showsRoots")

        val knownMovies = previous.movies.associateBy(Movie::folder)
        val knownShows = previous.shows.associateBy(Show::folder)
        val movies = moviesRoots.flatMap { scanMovies(it, knownMovies) }
        val shows = showsRoots.flatMap { scanShows(it, knownShows) }
        return Library(
            source = source,
            movies = movies.sortedWith { a, b -> naturalCompare(a.title, b.title) },
            shows = shows.sortedWith { a, b -> naturalCompare(a.title, b.title) },
            scannedAt = System.currentTimeMillis(),
        )
    }

    // --- Movies ---

    private suspend fun scanMovies(root: String, known: Map<String, Movie>): List<Movie> {
        val entries = list(root)
        return forEachParallel(entries, "Films") { movieFor(it, entries, known[it.path]) }
    }

    private suspend fun movieFor(entry: NasEntry, rootEntries: List<NasEntry>, known: Movie?): Movie? {
        val siblings = if (entry.isDirectory) list(entry.path) else rootEntries
        val video = when {
            entry.isDirectory -> siblings.filter { it.isVideo && !it.isExtra() }.maxByOrNull { it.size }
            entry.isVideo -> entry
            else -> null
        } ?: return null
        val subtitles = subtitlesFor(video, siblings).map { it.path }

        if (known?.tmdbId != null) return known.copy(file = video.path, fileSize = video.size, subtitles = subtitles)

        val parsed = parseMediaName(entry.name)
        val match = lookup("film ${entry.name}") { tmdb.searchMovie(parsed.title, parsed.year)?.let { tmdb.movie(it.id) } }
            ?: return Movie(entry.path, video.path, video.size, subtitles, title = parsed.title, year = parsed.year)
        return Movie(
            folder = entry.path,
            file = video.path,
            fileSize = video.size,
            subtitles = subtitles,
            tmdbId = match.id,
            title = match.title,
            originalTitle = match.originalTitle,
            year = match.releaseDate.year() ?: parsed.year,
            overview = match.overview,
            tagline = match.tagline?.ifBlank { null },
            poster = match.posterPath,
            backdrop = match.backdropPath,
            runtime = match.runtime?.takeIf { it > 0 },
            genres = match.genres.map { it.name },
            rating = match.voteAverage?.takeIf { it > 0 },
        )
    }

    // --- Shows ---

    private class EpisodeFile(val season: Int, val episode: Int, val video: NasEntry, val subtitles: List<String>)

    private suspend fun scanShows(root: String, known: Map<String, Show>): List<Show> {
        val folders = list(root).filter { it.isDirectory }
        return forEachParallel(folders, "Séries") { showFor(it, known[it.path]) }
    }

    private suspend fun showFor(folder: NasEntry, known: Show?): Show? {
        val files = episodeFiles(folder)
        if (files.isEmpty()) return null

        val show = known?.takeIf { it.tmdbId != null }?.copy(seasons = emptyList()) ?: matchShow(folder)
        val knownEpisodes = known?.seasons.orEmpty().flatMap { it.episodes }.associateBy { it.season to it.number }

        val seasons = files.groupBy { it.season }.map { (number, episodeFiles) ->
            val knownSeason = known?.seasons?.firstOrNull { it.number == number }
            val episodes = episodeFiles.sortedBy { it.episode }.map { file ->
                knownEpisodes[number to file.episode]?.takeIf { it.hasMetadata }
                    ?.copy(file = file.video.path, fileSize = file.video.size, subtitles = file.subtitles)
                    ?: Episode(number, file.episode, file.video.path, file.video.size, file.subtitles)
            }
            val season = Season(number, knownSeason?.name, knownSeason?.poster, episodes)
            if (show.tmdbId != null && episodes.any { !it.hasMetadata }) {
                lookup("saison $number de ${folder.name}") { withSeasonDetails(show.tmdbId, season) } ?: season
            } else {
                season
            }
        }
        return show.copy(seasons = seasons.sortedBy { it.number })
    }

    /** Episodes in season folders ("Saison 01/S01E01.mkv") or loose in the show folder. */
    private fun episodeFiles(folder: NasEntry): List<EpisodeFile> {
        val content = list(folder.path)
        val files = mutableListOf<EpisodeFile>()
        fun collect(entries: List<NasEntry>, folderSeason: Int?) {
            entries.filter { it.isVideo && !it.isExtra() }.forEach { video ->
                val (season, episode) = parseEpisodeFile(video.name) ?: return@forEach
                val number = season ?: folderSeason ?: 1
                files += EpisodeFile(number, episode, video, subtitlesFor(video, entries).map { it.path })
            }
        }
        collect(content, null)
        content.filter { it.isDirectory }.forEach { dir ->
            val season = parseSeasonFolder(dir.name) ?: return@forEach
            collect(list(dir.path), season)
        }
        // Two copies of an episode (e.g. 1080p and 4K): keep the biggest.
        return files.groupBy { it.season to it.episode }.map { (_, copies) -> copies.maxBy { it.video.size } }
    }

    private suspend fun matchShow(folder: NasEntry): Show {
        val parsed = parseMediaName(folder.name)
        val match = lookup("série ${folder.name}") { tmdb.searchShow(parsed.title, parsed.year)?.let { tmdb.show(it.id) } }
            ?: return Show(folder.path, title = parsed.title, year = parsed.year)
        return Show(
            folder = folder.path,
            tmdbId = match.id,
            title = match.name,
            originalTitle = match.originalName,
            year = match.firstAirDate.year() ?: parsed.year,
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

    private fun String?.year() = this?.take(4)?.toIntOrNull()

    private companion object {
        const val TAG = "LibraryScanner"
    }
}
