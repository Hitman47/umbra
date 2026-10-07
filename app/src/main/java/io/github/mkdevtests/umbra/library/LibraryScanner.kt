package io.github.mkdevtests.umbra.library

import android.util.Log
import io.github.mkdevtests.umbra.browse.isImageName
import io.github.mkdevtests.umbra.browse.isVideo
import io.github.mkdevtests.umbra.browse.naturalCompare
import io.github.mkdevtests.umbra.browse.subtitlesFor
import io.github.mkdevtests.umbra.history.MatchFix
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.nas.NasRouter
import io.github.mkdevtests.umbra.nas.isRefusedByNas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import io.github.mkdevtests.umbra.nas.within
import java.util.concurrent.ConcurrentHashMap
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

private val EXTRA_FILE = Regex("""(?i)sample|trailer|(?<![a-z])bonus(?![a-z])""")

/** Deep enough for "Share\Séries\Drame\Show\Saison 1", shallow enough to stop on loops. */
private const val MAX_DEPTH = 8

/** Age of a TheTVDB numbering asked again; half a day if a file is missing from it (a new episode). */
private const val NUMBERING_MAX_AGE = 7 * 24 * 3600_000L
private const val NUMBERING_RETRY_AGE = 12 * 3600_000L

/** Tries before a listing that times out fails the scan. */
private const val LIST_ATTEMPTS = 3

/** Folders failing one after the other: the NAS is gone, not one folder. */
private const val MAX_FAILING_IN_A_ROW = 25

/** Before the folders that didn't answer are asked again. */
private const val RETRY_FAILED_AFTER_MS = 5_000L

/**
 * Builds the library like Infuse: walks every selected share, whatever the
 * folder layout (genre folders, loose files, one folder per film), and tells
 * films from episodes by file name. Items already matched in [previous] keep
 * their metadata, so a rescan only costs listings plus lookups for new files.
 */
class LibraryScanner(
    private val nas: NasRouter,
    private val tmdb: Tmdb,
    /** The user's corrections, by group: they win over the TMDB search and the last scan. */
    private val fixes: Map<String, MatchFix> = emptyMap(),
    /** Groups whose correction was just removed: matched again rather than kept from the last scan. */
    private val rematch: Set<String> = emptySet(),
    /** Anime numbering; none without a TheTVDB key. */
    private val tvdb: Tvdb? = null,
    private val numberings: NumberingCache? = null,
    /** In the background (the launch's scan): fewer things at once, the screens stay smooth. */
    gentle: Boolean = false,
    /** The Spectacles and Concerts folders: their videos are films, looked up with their artist. */
    private val sectionRoots: List<String> = emptyList(),
    private val onProgress: (String) -> Unit,
) {
    /** Parallel TMDB calls: their latency overlaps without hitting the rate limit. */
    private val workers = Semaphore(if (gentle) 2 else 6)

    /** Parallel NAS listings: each waits on a round trip, the NAS serves many at once. */
    private val listings = Semaphore(if (gentle) 4 else 16)

    /** TMDB seasons fetched by this scan: a TheTVDB numbering needs them all, the episode details again. */
    private val tmdbSeasons = ConcurrentHashMap<Pair<Int, Int>, TmdbSeason>()

    /** Shares of the last scan whose NAS didn't answer: their titles were kept from the previous library. */
    var offline: Set<String> = emptySet()
        private set

    /** The folders an analysis cut off had listed: read from there, not from the NAS again. */
    var resume: ScanJournal? = null

    /** How each group of episodes got its show in the last [scan]: what Réglages › Corrections shows. */
    val decisions = java.util.concurrent.ConcurrentLinkedQueue<MatchDecision>()

    /** The images found next to the videos by the last [scan]. */
    var localArt = LocalArt()
        private set

    /** A video found on the NAS; [folders] are the folder names between the share and the file. */
    private class VideoFile(val entry: NasEntry, val folders: List<String>, val subtitles: List<String>)

    /**
     * An episode found on the NAS. [group] gathers the files of one show before
     * the TMDB lookup: its folder ("folder:Animes\Claymore") or its title.
     * [folder] is the show's own folder, if the file sits in one.
     */
    private data class EpisodeFile(
        val video: VideoFile,
        val show: ParsedName,
        val group: String,
        val season: Int,
        /** Null for an unnumbered special, named by [title] instead. */
        val episode: Int?,
        /** False when [season] is a default: [episode] may then count from the show's first episode. */
        val seasonKnown: Boolean = true,
        val folder: String? = null,
        val title: String? = null,
    )

    suspend fun scan(previous: Library, source: String): Library {
        val offline = ConcurrentHashMap.newKeySet<String>()
        val videos = walk(offline)
        this.offline = offline.toSet()
        if (videos.isEmpty()) {
            throw IOException(if (offline.isEmpty()) "Aucune vidéo trouvée dans les partages choisis." else "Le NAS ne répond plus : bibliothèque inchangée.")
        }

        val found = mutableListOf<EpisodeFile>()
        val movieFiles = mutableListOf<VideoFile>()
        videos.forEach { video -> episodeOf(video)?.let(found::add) ?: movieFiles.add(video) }
        // A folder with nothing but unnumbered specials holds films: "Akira\Specials\Akira.mkv".
        val (filmsOnly, showGroups) = found.groupBy { it.group }.values.partition { group -> group.all { it.episode == null } }
        filmsOnly.flatten().mapTo(movieFiles) { it.video }
        val episodes = showGroups.flatten()
        Log.i(TAG, "${videos.size} videos: ${movieFiles.size} films, ${episodes.size} episodes")

        val counts = videoCounts(videos)
        // Every file the last scan saw, smaller copies included.
        val knownFiles = previous.movies.flatMap { movie ->
            listOf(movie.file to movie) + movie.copies.map { it.file to movie.copy(file = it.file, fileSize = it.size, modified = it.modified) }
        }.toMap()
        val movies = scanMovies(movieFiles, knownFiles, counts)
        val shows = scanShows(episodes, previous.shows, counts)
        val scanned = keepOffline(Library(movies = movies, shows = shows), previous, this.offline + failed)
        return Library(
            version = Library.VERSION,
            source = source,
            movies = scanned.movies.sortedWith { a, b -> naturalCompare(a.title, b.title) },
            shows = scanned.shows.sortedWith { a, b -> naturalCompare(a.title, b.title) },
            scannedAt = System.currentTimeMillis(),
        )
    }

    /**
     * A correction applied at once, without walking the NAS: the shows holding
     * [groups], and the one the correction names ([tmdbId]), are made again
     * from the files [previous] knows, with the same rules as a whole scan.
     */
    suspend fun rematch(previous: Library, groups: Set<String>, tmdbId: Int?): Library {
        val affected = previous.shows.filter { show -> show.groups.any { it in groups } || (tmdbId != null && show.tmdbId == tmdbId) }
        if (affected.isEmpty()) return previous
        val all = previous.videoFiles()
        val keys = affected.mapTo(HashSet()) { it.key }
        val files = affected.flatMapTo(HashSet()) { show -> show.seasons.flatMap { it.episodes }.map { it.file } + show.duplicates.map(EpisodeCopy::pathOf) }
        val episodes = all.filter { it.entry.path in files }.mapNotNull(::episodeOf)
        val shows = scanShows(episodes, previous.shows, videoCounts(all))
        return previous.copy(
            shows = (previous.shows.filter { it.key !in keys } + shows).sortedWith { a, b -> naturalCompare(a.title, b.title) },
        )
    }

    /** The videos of [this] library as the NAS walk found them: films, their copies, episodes and theirs. */
    private fun Library.videoFiles(): List<VideoFile> {
        fun video(path: String, size: Long, modified: Long, subtitles: List<String>): VideoFile {
            val parts = path.split('\\')
            return VideoFile(NasEntry(parts.last(), path, isDirectory = false, size = size, modified = modified), parts.drop(1).dropLast(1), subtitles)
        }
        return movies.flatMap { movie -> listOf(video(movie.file, movie.fileSize, movie.modified, movie.subtitles)) + movie.copies.map { video(it.file, it.size, it.modified, emptyList()) } } +
            shows.flatMap { show ->
                show.seasons.flatMap { it.episodes }.map { video(it.file, it.fileSize, it.modified, it.subtitles) } +
                    show.duplicates.mapNotNull(EpisodeCopy::decode).map { video(it.file, it.size, 0, emptyList()) }
            }
    }

    // --- Walking the shares ---

    /** Folders that didn't answer, even when asked again at the end: their titles stay as the last analysis left them. */
    var failed: Set<String> = emptySet()
        private set

    /**
     * The videos of every share; a share whose NAS doesn't answer is added to
     * [offline] instead, a folder that doesn't answer to [failed] (asked again
     * once at the end): an analysis of tens of thousands of folders no longer
     * fails for one of them.
     */
    private suspend fun walk(offline: MutableSet<String>): List<VideoFile> {
        val found = ConcurrentLinkedQueue<VideoFile>()
        val art = ConcurrentLinkedQueue<LocalArt>()
        val folders = AtomicInteger()
        val failing = ConcurrentHashMap<String, List<String>>()
        val inARow = AtomicInteger()

        suspend fun explore(starts: List<Pair<String, List<String>>>) = coroutineScope {
            fun visit(path: String, names: List<String>) {
                launch(Dispatchers.IO) {
                    val entries = listings.withPermit {
                        try {
                            listOrSkip(path).also { inARow.set(0) }
                        } catch (e: IOException) {
                            // A share's root: its NAS may be off or out of reach, the other NAS are scanned all the same.
                            if (names.isEmpty()) {
                                offline += path
                            } else {
                                // Many in a row: the NAS itself is gone, the analysis stops (library unchanged).
                                if (inARow.incrementAndGet() >= MAX_FAILING_IN_A_ROW) throw e
                                failing[path] = names
                            }
                            return@withPermit null
                        }
                    } ?: return@launch
                    failing.remove(path)
                    val videos = entries.filter { it.isVideo && !it.isExtra() }
                    videos.forEach { video ->
                        found += VideoFile(video, names, subtitlesFor(video, entries).map { it.path })
                    }
                    // folder.jpg and the like: the folder's or a video's own artwork.
                    val images = entries.filter { !it.isDirectory && isImageName(it.name) }.map { it.path }
                    if (images.isNotEmpty()) art += folderArt(path, images, videos.map { it.path })
                    onProgress("Exploration du NAS : ${folders.incrementAndGet()} dossiers, ${found.size} vidéos")
                    if (names.size < MAX_DEPTH) {
                        entries.filter { it.isDirectory && !it.isSkipped() }.forEach { visit(it.path, names + it.name) }
                    }
                }
            }
            starts.forEach { (path, names) -> visit(path, names) }
        }

        explore(list("").map { it.path to emptyList() })
        if (failing.isNotEmpty()) {
            // The NAS had a hiccup: the folders that didn't answer, once more.
            delay(RETRY_FAILED_AFTER_MS)
            onProgress("Exploration du NAS : nouvel essai de ${failing.size} dossiers")
            explore(failing.map { (path, names) -> path to names })
        }
        failed = failing.keys.toSet()
        if (failed.isNotEmpty()) Log.w(TAG, "${failed.size} folders kept as they were: ${failed.take(5)}")
        localArt = LocalArt(art.flatMap { it.posters.entries }.associate { it.toPair() }, art.flatMap { it.backdrops.entries }.associate { it.toPair() })
        return found.toList()
    }

    /**
     * [path]'s entries, or none for a folder the NAS refuses (rights). A NAS
     * that stops answering fails the scan instead, after a few tries: a scan
     * missing whole folders would drop their titles from the library.
     */
    private suspend fun listOrSkip(path: String): List<NasEntry> {
        resume?.get(path)?.let { return it }
        repeat(LIST_ATTEMPTS) { attempt ->
            try {
                return list(path).also { resume?.put(path, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isRefusedByNas()) {
                    Log.w(TAG, "cannot list $path", e)
                    return emptyList()
                }
                Log.w(TAG, "cannot list $path, attempt ${attempt + 1}", e)
                if (attempt == LIST_ATTEMPTS - 1) throw IOException("Le NAS ne répond plus (${path.substringAfterLast('\\')}) : bibliothèque inchangée.", e)
                delay(2_000L * (attempt + 1))
            }
        }
        error("unreachable")
    }

    /** Number of videos under each folder, at any depth ("Films\Drame" → 212). Share roots are left out. */
    private fun videoCounts(videos: List<VideoFile>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        videos.forEach { video ->
            var folder = video.entry.path.substringBeforeLast('\\', "")
            while ('\\' in folder) {
                counts.merge(folder, 1, Int::plus)
                folder = folder.substringBeforeLast('\\')
            }
        }
        return counts
    }

    /** The episode a video is, or null for a film. */
    private fun episodeOf(video: VideoFile): EpisodeFile? {
        // A spectacle in two parts is not a show.
        if (sectionRoots.any { video.entry.path.within(it) }) return null
        val folderSeason = video.folders.lastOrNull()?.let(::parseSeasonFolder)
        if (folderSeason != null) seasonFolderEpisode(video, folderSeason)?.let { return it }
        val name = parseEpisodeName(video.entry.name, inSeasonFolder = folderSeason != null) ?: return null
        // The title in the file name wins: the folder may be a genre ("Séries\Drame\Show.S01E01.mkv").
        val parent = video.folders.lastOrNull { parseSeasonFolder(it) == null }
        val show = name.show ?: parent?.let(::parseMediaName) ?: return null
        // The parent is the show's own folder when it bears its name ("Animes\Claymore\Claymore - 05.mkv").
        val folder = video.entry.path.substringBeforeLast('\\')
            .takeIf { folderSeason == null && parent != null && normalizeTitle(parseMediaName(parent).title) == normalizeTitle(show.title) }
        return EpisodeFile(
            video, show, "title:${normalizeTitle(show.title)}",
            season = name.season ?: folderSeason ?: 1,
            episode = name.episode,
            seasonKnown = name.season != null || folderSeason != null,
            folder = folder,
        )
    }

    /**
     * Infuse's rule: a video in "Show\Saison 2" is an episode of Show, whatever
     * its name, as long as a number can be read from it ("Claymore.E19",
     * "Shingeki No Kyojin 54", "S0106_HD"). Unnumbered videos in "Spéciaux"
     * are specials, unless named like a film ("Specials\El Camino (2019)").
     */
    private fun seasonFolderEpisode(video: VideoFile, folderSeason: Int): EpisodeFile? {
        val fileName = video.entry.name
        if (hasMediaId(fileName)) return null // "Specials\El Camino {tmdb-559969}" is a film
        val folders = video.folders
        val showIndex = folders.indexOfLast { parseSeasonFolder(it) == null && !isSeasonsContainer(it) }
        if (showIndex < 0) return null
        val show = parseMediaName(folders[showIndex]).takeIf { it.title.isNotBlank() } ?: return null
        val folder = (listOf(video.entry.path.substringBefore('\\')) + folders.take(showIndex + 1)).joinToString("\\")
        val group = "folder:$folder"

        val number = parseEpisodeName(fileName, inSeasonFolder = true) ?: parseEpisodeNumber(fileName)
        if (number != null) return EpisodeFile(video, show, group, number.season ?: folderSeason, number.episode, folder = folder)
        if (folderSeason != 0 || isTitleWithYear(fileName)) return null
        return EpisodeFile(video, show, group, 0, episode = null, folder = folder, title = parseMediaName(fileName).title)
    }

    /** "Show\Saisons\Saison 1": a folder grouping the seasons is not the show. */
    private fun isSeasonsContainer(name: String) = name.withoutAccents().lowercase().trim() in setOf("saisons", "seasons")

    // --- Films ---

    private suspend fun scanMovies(files: List<VideoFile>, known: Map<String, Movie>, counts: Map<String, Int>): List<Movie> {
        val names = files.associateWith(::movieName)
        // One lookup per title, even when the same film is there twice.
        val groups = files.groupBy { file ->
            names.getValue(file).let { it.imdbId ?: it.tmdbId?.let { id -> "tmdb:$id" } ?: "${artist(file.entry.path).orEmpty()}|${normalizeTitle(it.title)}|${it.year}" }
        }.values.toList()
        val movies = forEachParallel(groups, "Films") { group ->
            val name = names.getValue(group.first())
            val template = group.firstNotNullOfOrNull { known[it.entry.path]?.takeIf { movie -> movie.tmdbId != null } }
                // Matched before credits or sagas were fetched: refresh it.
                ?.let { movie -> if (movie.hasCredits && movie.sagaChecked && movie.linksChecked) movie else lookup("film ${movie.title}") { tmdb.movie(movie.tmdbId!!).toMovie(name) } ?: movie }
                // Unchanged since TMDB found nothing for it: don't ask again.
                ?: group.firstNotNullOfOrNull { known[it.entry.path]?.takeIf { movie -> unchanged(movie.fileSize, movie.modified, it.entry) } }
                ?: lookup("film ${group.first().entry.name}") {
                    val id = name.tmdbId
                        ?: name.imdbId?.let { tmdb.movieForImdb(it) }
                        ?: tmdb.findMovie(artistQueries(artist(group.first().entry.path), name) + searchQueries(name), name.year)
                    id?.let { tmdb.movie(it).toMovie(name) }
                }
                ?: Movie(file = "", fileSize = 0, title = name.title, year = name.year)
            group.map { template.copy(file = it.entry.path, fileSize = it.entry.size, modified = it.entry.modified, subtitles = it.subtitles) }
        }.flatten()
        // Several copies of a film (1080p and 4K, or in two genre folders): keep the biggest.
        return movies
            .groupBy { it.tmdbId?.toString() ?: "${artist(it.file).orEmpty()}|${normalizeTitle(it.title)}|${it.year}" }
            .map { (_, copies) ->
                val kept = copies.maxBy { it.fileSize }
                kept.copy(
                    folder = filmFolder(kept, copies, counts),
                    copies = (copies - kept).map { FileCopy(it.file, it.fileSize, it.modified) },
                )
            }
    }

    /** The folder of [movie] when it holds only this film and bears its name: "Films\Drame\Dune (2021)", not "Films\Drame". */
    private fun filmFolder(movie: Movie, copies: List<Movie>, counts: Map<String, Int>): String? {
        val folder = movie.file.substringBeforeLast('\\')
        if ('\\' !in folder) return null // a share root
        val name = folder.substringAfterLast('\\')
        val titles = listOfNotNull(movie.title, movie.originalTitle, parseMediaName(movie.file.substringAfterLast('\\')).title).map(::normalizeTitle)
        val named = isTitleWithYear(name) || normalizeTitle(parseMediaName(name).title) in titles
        return folder.takeIf { named && counts[it] == copies.count { copy -> copy.file.startsWith("$it\\") } }
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
        cast = credits?.actors().orEmpty(),
        directors = credits?.crew.orEmpty().filter { it.job == "Director" }.map { it.name }.distinct(),
        hasCredits = credits != null,
        sagaId = collection?.id,
        saga = collection?.name,
        sagaPoster = collection?.posterPath,
        sagaChecked = true,
        characters = leadCharacters(credits?.cast.orEmpty().map { it.character }),
        universes = franchiseKeywords(keywords?.all.orEmpty().map { it.name }),
        linksChecked = true,
    )

    // --- Shows ---

    private suspend fun scanShows(episodes: List<EpisodeFile>, previous: List<Show>, counts: Map<String, Int>): List<Show> {
        val knownShowOf = previous.flatMap { show ->
            (show.seasons.flatMap { it.episodes }.map { it.file } + show.duplicates.map(EpisodeCopy::pathOf)).map { it to show }
        }.toMap()
        val knownEpisodes = previous.flatMap { show -> show.seasons.flatMap { it.episodes }.map { it.copy(showKey = show.key) } }.associateBy { it.file }
        val previousByKey = previous.associateBy { it.key }

        // 1. Which show each folder or title is.
        val groups = episodes.groupBy { it.group }.values.toList()
        val identified = forEachParallel(groups, "Séries") { files ->
            val fix = fixes[files.first().group]
            val group = fix?.let { files.map { file -> file.fixed(it) } } ?: files
            val known = group.firstNotNullOfOrNull { knownShowOf[it.video.entry.path]?.takeIf { show -> show.tmdbId != null } }
                ?.takeIf { group.first().group !in rematch }
            val name = group.groupingBy { it.show }.eachCount().maxBy { it.value }.key
            val (show, how) = when {
                fix != null -> fixedShow(fix, name, previousByKey) to "correction"
                known == null -> matchShow(name) to "recherche TMDB « ${name.title} »"
                // Matched by an older version, without the season sizes or the credits: refresh it.
                known.seasonEpisodes.isEmpty() || !known.hasCredits || !known.linksChecked || known.detailsVersion < SHOW_DETAILS_VERSION -> (lookup("série ${known.title}") { tmdb.show(known.tmdbId!!).toShow(name) } ?: known) to "reconnue avant"
                else -> known to "reconnue avant"
            }
            decisions += MatchDecision(group.first().group, group.size, how, show.key, show.title, show.year, show.tmdbId)
            show.copy(seasons = emptyList(), folders = emptyList()) to group
        }
        // "Show" and "Show (2019)", or two folders of one show, may be the same TMDB show.
        val merged = identified.groupBy { it.first.key }.map { (_, parts) -> parts.first().first to parts.flatMap { it.second } }

        // 2. Seasons, with TMDB episode details for files not known yet.
        return forEachParallel(merged, "Épisodes") { (show, all) ->
            val files = numberSpecials(withTvdbNumbers(show, all).map { it.renumbered(show.seasonEpisodes) })
            val unique = files.groupBy { it.season to it.episode }.map { (_, copies) -> copies.maxBy { it.video.entry.size } }
            val seasons = unique.groupBy { it.season }.map { (number, seasonFiles) ->
                val episodesOfSeason = seasonFiles.sortedBy { it.episode }.map { file ->
                    val video = file.video
                    val episode = file.episode!!
                    knownEpisodes[video.entry.path]
                        // Same place in the same show: a corrected match takes the new show's details.
                        ?.takeIf { it.showKey == show.key && it.season == number && it.number == episode }
                        ?.takeIf { it.hasMetadata || unchanged(it.fileSize, it.modified, video.entry) }
                        ?.copy(fileSize = video.entry.size, modified = video.entry.modified, subtitles = video.subtitles)
                        ?: Episode(number, episode, video.entry.path, video.entry.size, video.subtitles, title = file.title, modified = video.entry.modified)
                }
                val knownSeason = previousByKey[show.key]?.seasons?.firstOrNull { it.number == number }
                val season = Season(number, knownSeason?.name, knownSeason?.poster, episodesOfSeason)
                // A season TMDB doesn't list would only answer 404.
                val onTmdb = show.seasonEpisodes.isEmpty() || number in show.seasonEpisodes
                // Episodes TMDB had no details for, unchanged since: not asked again.
                val missing = episodesOfSeason.any { episode ->
                    !episode.hasMetadata &&
                        knownEpisodes[episode.file]?.takeIf { !it.hasMetadata && it.showKey == show.key }?.let { unchanged(it.fileSize, it.modified, episode) } != true
                }
                if (show.tmdbId != null && onTmdb && missing) {
                    lookup("saison $number de ${show.title}") { withSeasonDetails(show.tmdbId, season) } ?: season
                } else {
                    season
                }
            }
            val shown = unique.mapTo(HashSet()) { it.video.entry.path }
            show.copy(
                seasons = seasons.sortedBy { it.number },
                folders = showFolders(all, counts),
                // The other files of an episode, offered as versions on its page.
                duplicates = files.filter { it.video.entry.path !in shown && it.episode != null }
                    .map { EpisodeCopy(it.season, it.episode!!, it.video.entry.size, it.video.entry.path).encode() },
                groups = all.map { it.group }.distinct().sorted(),
            )
        }
    }

    /**
     * An absolute number ("Shingeki No Kyojin 54" in Saison 03) in the season's
     * own numbering, from the TMDB season sizes: 54 = S03E17 after 25 + 12
     * episodes. Without a season ("[Group] Show - 30"), finds the season too.
     */
    private fun EpisodeFile.renumbered(sizes: Map<Int, Int>): EpisodeFile {
        val number = episode ?: return this
        val size = sizes[season] ?: return this
        if (season < 1 || number <= size) return this
        if (!seasonKnown) {
            var before = 0
            for (s in sizes.keys.filter { it >= 1 }.sorted()) {
                val count = sizes.getValue(s)
                if (number <= before + count) return copy(season = s, episode = number - before)
                before += count
            }
            return this
        }
        val before = sizes.filterKeys { it in 1 until season }.values.sum()
        return if (number - before in 1..size) copy(episode = number - before) else this
    }

    /**
     * Episodes TMDB can't place by their numbers ("One Piece - 1050", "Saison 3\54",
     * or seasons cut otherwise than TMDB's), placed through TheTVDB, which
     * knows each episode's absolute number and air date. A whole season folder
     * (or all the files without a season) goes through it, so that its
     * episodes are numbered one way. The others keep their numbers.
     */
    private suspend fun withTvdbNumbers(show: Show, files: List<EpisodeFile>): List<EpisodeFile> {
        val sizes = show.seasonEpisodes
        val id = show.tmdbId
        if (tvdb == null || numberings == null || id == null || sizes.isEmpty()) return files
        val numbered = files.filter { it.episode != null && it.season >= 1 && it.group !in fixes }
        val off = numbered.filter { file -> sizes[file.season]?.let { file.episode!! > it } ?: true }
        if (off.isEmpty()) return files
        val batches = off.mapTo(HashSet()) { it.seasonKnown to it.season }
        val odd = numbered.filter { (it.seasonKnown to it.season) in batches }
        val numbering = numberingOf(show, odd) ?: return files
        val placed = odd.associateWith { numbering.place(it.season, it.episode!!, it.seasonKnown) }
        return files.map { file -> placed[file]?.let { (season, number) -> file.copy(season = season, episode = number, seasonKnown = true) } ?: file }
    }

    /** The cached numbering of [show], or TheTVDB's when old or missing an episode of [files]. */
    private suspend fun numberingOf(show: Show, files: List<EpisodeFile>): Numbering? {
        val id = show.tmdbId ?: return null
        val cache = numberings ?: return null
        val cached = cache[id]
        val now = System.currentTimeMillis()
        val age = cached?.let { now - it.fetchedAt } ?: Long.MAX_VALUE
        val complete = cached != null && files.all { cached.place(it.season, it.episode!!, it.seasonKnown) != null }
        if (age < NUMBERING_MAX_AGE && (complete || age < NUMBERING_RETRY_AGE)) return cached
        return lookup("TheTVDB ${show.title}") {
            // Unknown to TheTVDB: an empty numbering, not asked again before long.
            val episodes = tmdb.tvdbId(id)?.let { tvdb!!.episodes(it) }.orEmpty()
            val slots = if (episodes.isEmpty()) {
                emptyList()
            } else {
                show.seasonEpisodes.keys.filter { it >= 1 }.sorted().flatMap { number ->
                    tmdbSeason(id, number).episodes.map { TmdbSlot(number, it.number, it.airDate) }
                }
            }
            Numbering(now, pairEpisodes(episodes, slots)).also { cache[id] = it }
        } ?: cached
    }

    private suspend fun tmdbSeason(showId: Int, number: Int): TmdbSeason =
        tmdbSeasons[showId to number] ?: tmdb.season(showId, number).also { tmdbSeasons[showId to number] = it }

    /** Unnumbered specials go after the numbered ones of season 0, in name order. */
    private fun numberSpecials(files: List<EpisodeFile>): List<EpisodeFile> {
        val unnumbered = files.filter { it.episode == null }
        if (unnumbered.isEmpty()) return files
        val last = files.filter { it.season == 0 }.maxOfOrNull { it.episode ?: 0 } ?: 0
        return files.filter { it.episode != null } +
            unnumbered.sortedWith { a, b -> naturalCompare(a.video.entry.name, b.video.entry.name) }
                .mapIndexed { i, file -> file.copy(episode = last + i + 1) }
    }

    /** The show's folders that hold nothing else, so that browsing them shows the show. */
    private fun showFolders(files: List<EpisodeFile>, counts: Map<String, Int>): List<String> =
        files.mapNotNull { it.folder }.distinct().filter { folder ->
            counts[folder] == files.count { it.video.entry.path.startsWith("$folder\\") }
        }

    /** The files of a corrected group, in the season the user chose. Specials keep their numbers. */
    private fun EpisodeFile.fixed(fix: MatchFix): EpisodeFile {
        val season = fix.season ?: return this
        if (this.season == 0 && season != 0) return this
        return copy(season = season, episode = episode?.let { it + fix.firstEpisode - 1 }, seasonKnown = true)
    }

    /** The show the user chose; reuses the last scan's details when it had them. */
    private suspend fun fixedShow(fix: MatchFix, name: ParsedName, previous: Map<String, Show>): Show {
        val id = fix.tmdbId ?: return Show(key = "title:${normalizeTitle(name.title)}", title = name.title, year = name.year)
        previous["tmdb:$id"]?.takeIf { it.seasonEpisodes.isNotEmpty() && it.hasCredits && it.linksChecked }?.let { return it }
        return lookup("série $id") { tmdb.show(id).toShow(name) }
            ?: Show(key = "tmdb:$id", tmdbId = id, title = name.title, year = name.year) // retried by the next scan
    }

    private suspend fun matchShow(name: ParsedName): Show {
        val unmatched = Show(key = "title:${normalizeTitle(name.title)}", title = name.title, year = name.year)
        return lookup("série ${name.title}") {
            tmdb.findShow(searchQueries(name), name.year)?.let { tmdb.show(it).toShow(name) }
        } ?: unmatched
    }

    private fun TmdbShow.toShow(name: ParsedName) = Show(
        key = "tmdb:$id",
        tmdbId = id,
        title = this.name,
        originalTitle = originalName,
        year = firstAirDate.year() ?: name.year,
        overview = overview,
        poster = posterPath,
        backdrop = backdropPath,
        genres = genres.map { it.name },
        rating = voteAverage?.takeIf { it > 0 },
        status = status,
        seasonEpisodes = seasons.associate { it.number to it.episodeCount },
        cast = credits?.actors().orEmpty(),
        directors = createdBy.map { it.name }.distinct(),
        hasCredits = credits != null,
        characters = leadCharacters(credits?.cast.orEmpty().map { it.character }),
        universes = franchiseKeywords(keywords?.all.orEmpty().map { it.name }),
        linksChecked = true,
        originCountries = originCountry,
        originalLanguage = originalLanguage,
        seasonInfo = seasons.map { SeasonInfo(it.number, it.overview?.ifBlank { null }, it.airDate?.ifBlank { null }, it.voteAverage?.takeIf { v -> v > 0 }, it.episodeCount) },
        detailsVersion = SHOW_DETAILS_VERSION,
    )

    private suspend fun withSeasonDetails(showId: Int, season: Season): Season {
        val details = tmdbSeason(showId, season.number)
        val byNumber = details.episodes.associateBy { it.number }
        return season.copy(
            name = details.name,
            poster = details.posterPath,
            episodes = season.episodes.map { episode ->
                if (episode.hasMetadata) return@map episode // known already, or a special named by its file
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

    /** The artist of a spectacle or a concert (see [artistOf]); null elsewhere. */
    private fun artist(path: String): String? = if (sectionRoots.isEmpty()) null else artistOf(path, sectionRoots)

    /** "Artiste Titre" first: TMDB names many spectacles and concerts after both. */
    private fun artistQueries(artist: String?, name: ParsedName): List<String> =
        if (artist == null || normalizeTitle(name.title).contains(normalizeTitle(artist))) emptyList() else listOf("$artist ${name.title}")

    private fun list(path: String) = nas.list(path)

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

    /** "Film.sample.mkv", "The.Lost.Room.BONUS.mkv": not part of the library (the share browser still shows them). */
    private fun NasEntry.isExtra() = EXTRA_FILE.containsMatchIn(name)

    /** "Bonus [Edition 25eme Anniversaire]" counts as "bonus". */
    private fun NasEntry.isSkipped() =
        name.startsWith('.') || name.lowercase().substringBefore('[').substringBefore('(').trim() in SKIPPED_FOLDERS

    /** Same size and write time as at the last scan (0 = written before times were kept). */
    private fun unchanged(size: Long, modified: Long, entry: NasEntry) = modified != 0L && size == entry.size && modified == entry.modified

    private fun unchanged(size: Long, modified: Long, episode: Episode) = modified != 0L && size == episode.fileSize && modified == episode.modified

    private fun String?.year() = this?.take(4)?.toIntOrNull()

    private companion object {
        const val TAG = "LibraryScanner"
    }
}

/**
 * [scanned] completed with what [previous] had on the [offline] shares (a NAS
 * that didn't answer), kept as it was until that NAS answers again. A film
 * found elsewhere too is shown from there.
 */
fun keepOffline(scanned: Library, previous: Library, offline: Set<String>): Library {
    if (offline.isEmpty()) return scanned
    // A share's root, or a folder of it.
    fun isOffline(file: String) = offline.any { file.within(it) }
    val ids = scanned.movies.mapNotNullTo(HashSet()) { it.tmdbId }
    val movies = scanned.movies + previous.movies.filter { isOffline(it.file) && (it.tmdbId == null || it.tmdbId !in ids) }

    val kept = previous.shows.mapNotNull { show ->
        val seasons = show.seasons.map { season -> season.copy(episodes = season.episodes.filter { isOffline(it.file) }) }.filter { it.episodes.isNotEmpty() }
        if (seasons.isEmpty()) null else show.copy(seasons = seasons)
    }.associateBy { it.key }
    val shows = scanned.shows.map { show -> kept[show.key]?.let { show.mergedWith(it) } ?: show } +
        kept.values.filter { show -> scanned.shows.none { it.key == show.key } }
    return scanned.copy(movies = movies, shows = shows)
}

/** [this] show with the episodes of [other] it doesn't have. */
private fun Show.mergedWith(other: Show): Show {
    val numbers = (seasons.map { it.number } + other.seasons.map { it.number }).distinct().sorted()
    return copy(
        seasons = numbers.map { number ->
            val mine = seasons.firstOrNull { it.number == number }
            val theirs = other.seasons.firstOrNull { it.number == number }
            val episodes = mine?.episodes.orEmpty() +
                theirs?.episodes.orEmpty().filter { episode -> mine?.episodes.orEmpty().none { it.number == episode.number } }
            (mine ?: theirs!!).copy(episodes = episodes.sortedBy { it.number })
        },
    )
}
