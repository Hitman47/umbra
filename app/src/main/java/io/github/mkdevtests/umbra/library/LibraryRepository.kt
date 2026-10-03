package io.github.mkdevtests.umbra.library

import android.util.Log
import io.github.mkdevtests.umbra.BuildConfig
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.browse.naturalCompare
import io.github.mkdevtests.umbra.history.MatchFix
import io.github.mkdevtests.umbra.nas.NasRouter
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

data class ScanState(val running: Boolean = false, val progress: String? = null, val error: String? = null)

/** Holds the library in memory, persists it to the database and runs scans. */
class LibraryRepository(private val app: NyxaraApp) {

    private val dao by lazy { LibraryDatabase.open(app).dao() }
    /** Where earlier versions kept the library: imported once, so its TMDB matches aren't looked up again. */
    private val legacyFile = File(app.filesDir, "library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tmdb by lazy { Tmdb(BuildConfig.TMDB_TOKEN, OkHttpClient()) }
    private val tvdb by lazy { BuildConfig.THETVDB_TOKEN.takeIf { it.isNotBlank() }?.let { Tvdb(it, OkHttpClient()) } }
    private var scanJob: Job? = null
    /** Groups whose correction was removed, matched again by the next scan. */
    private val rematch = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _library = MutableStateFlow(Library())

    /** Images found next to the videos (folder.jpg…), kept apart: TMDB's artwork stays in the database. */
    private val artFile = File(app.filesDir, "local-art.json")
    private val _localArt = MutableStateFlow(runCatching { Json.decodeFromString(LocalArt.serializer(), artFile.readText()) }.getOrDefault(LocalArt()))
    val localArt: StateFlow<LocalArt> = _localArt.asStateFlow()

    /** The library as shown: NAS images in place of TMDB's where the NAS has some. */
    val library: StateFlow<Library> = combine(_library, _localArt) { library, art -> library.withLocalArt(art) }
        .stateIn(scope, SharingStarted.Eagerly, Library())

    /** When the library on the device was written last: what the next launch starts from. */
    private val _savedAt = MutableStateFlow<Long?>(null)
    val savedAt: StateFlow<Long?> = _savedAt.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val loadJob = scope.launch {
        val stored = runCatching { dao.load() ?: importLegacyFile() }
            .onFailure { Log.w(TAG, "library unreadable, rescanning", it) }
            .getOrNull() ?: return@launch
        _savedAt.value = stored.scannedAt
        // An older format is shown until the rescan it triggers; its TMDB matches are reused.
        _library.value = if (stored.version == Library.VERSION) stored.sorted() else stored.copy(scannedAt = 0)
    }

    /** Films, shows and episodes whose title matches what the user typed, then films and shows by actor or director. */
    suspend fun search(text: String, limit: Int = 300): SearchHits {
        loadJob.join()
        val titles = ftsQuery(text, "text")?.let { dao.search(it, limit) }.orEmpty()
        val people = ftsQuery(text, "people")?.let { dao.search(it, limit) }.orEmpty()
        return SearchHits(titles, people)
    }

    /** Scans at launch if the user wants it, or when no scan of the current shares exists. */
    fun scanIfNeeded() {
        scope.launch {
            loadJob.join()
            val nas = app.nas ?: return@launch
            val stale = _library.value.source != keyOf(nas.sources) || _library.value.scannedAt == 0L
            if (stale || app.settings.settings.value.rescanAtLaunch) startScan()
        }
    }

    /** Shows on TMDB for what the user typed, to correct a match. */
    suspend fun searchShows(query: String): List<TmdbSearchItem> = tmdb.searchShows(query)

    /** A title page's TMDB details, kept for the session: opening it again costs nothing. */
    private val extras = java.util.concurrent.ConcurrentHashMap<String, Extras>()

    suspend fun extras(movie: Movie): Extras? {
        val id = movie.tmdbId ?: return null
        extras["m:$id"]?.let { return it }
        val details = tmdb.movieExtras(id)
        val saga = details.collection?.let { runCatching { tmdb.collection(it.id) }.getOrNull() }
        return movieExtras(movie, details, saga, _library.value).also { extras["m:$id"] = it }
    }

    private val sagas = java.util.concurrent.ConcurrentHashMap<Int, TmdbCollection>()

    /** A saga's page; null offline. */
    suspend fun saga(id: Int): SagaPage? {
        val collection = sagas[id] ?: runCatching { tmdb.collection(id) }.getOrNull()?.also { sagas[id] = it } ?: return null
        return sagaPage(collection, _library.value)
    }

    suspend fun extras(show: Show): Extras? {
        val id = show.tmdbId ?: return null
        extras["t:$id"]?.let { return it }
        return showExtras(show, tmdb.showExtras(id), _library.value).also { extras["t:$id"] = it }
    }

    /** TMDB seasons of a show, to place a folder in one of them. */
    suspend fun seasonsOf(tmdbId: Int): List<TmdbSeasonSummary> = tmdb.show(tmdbId).seasons

    /** Saves the user's choice for [groups] and rescans, which applies it. */
    fun fixMatch(groups: List<String>, tmdbId: Int?, season: Int?, firstEpisode: Int) {
        scope.launch {
            app.matchFixes.save(groups.map { MatchFix(it, tmdbId, season, firstEpisode) })
            rematch -= groups.toSet()
            restartScan()
        }
    }

    /** Back to the automatic match for [groups]. */
    fun resetMatch(groups: List<String>) {
        scope.launch {
            app.matchFixes.remove(groups)
            rematch += groups
            restartScan()
        }
    }

    /** A running scan was started without the latest correction: start over. */
    private suspend fun restartScan() {
        scanJob?.let { it.cancel(); it.join() }
        startScan()
    }

    /** A source was added, edited or removed: its titles go at once, the scan finds the new ones. */
    fun onSourcesChanged() {
        scope.launch {
            loadJob.join()
            _library.value = _library.value.within(app.nas)
            restartScan()
        }
    }

    /** A folder was left out: its titles go, nothing else to scan. */
    fun onFolderExcluded() {
        scope.launch {
            loadJob.join()
            val library = _library.value.within(app.nas)
            _library.value = library
            runCatching { save(library) }.onFailure { Log.w(TAG, "library not saved", it) }
        }
    }

    @Synchronized
    fun startScan() {
        if (scanJob?.isActive == true) return
        val nas = app.nas ?: return
        scanJob = scope.launch {
            loadJob.join()
            _scan.value = ScanState(running = true, progress = "Lecture du NAS…")
            try {
                val key = keyOf(nas.sources)
                // Titles of a share no longer chosen are dropped; the others' matches are reused.
                val previous = _library.value.within(nas).also { _library.value = it }
                val fixes = app.matchFixes.load()
                val again = synchronized(rematch) { rematch.toSet() }
                val numberings = NumberingCache(File(app.filesDir, "tvdb-numbering.json"))
                val scanner = LibraryScanner(nas, tmdb, fixes, again, tvdb, numberings) { _scan.value = ScanState(running = true, progress = it) }
                val started = System.currentTimeMillis()
                val requests = tmdb.requests.get()
                val tvdbRequests = tvdb?.requests?.get() ?: 0
                val result = scanner.scan(previous, key)
                _library.value = result
                extras.clear() // "in the library" may have changed
                try {
                    save(result)
                    // Read back: the next launch starts from what is on the device, not from memory.
                    check(dao.meta()?.scannedAt == result.scannedAt) { "relecture différente" }
                    _savedAt.value = result.scannedAt
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "library not saved", e)
                    _scan.value = ScanState(error = "Bibliothèque non enregistrée sur l'appareil (${e.message ?: e}) : le prochain lancement repartira de l'ancienne.")
                    return@launch
                }
                numberings.save()
                // A share that didn't answer keeps its images as they were.
                val art = scanner.localArt + _localArt.value.under(scanner.offline)
                _localArt.value = art
                runCatching { artFile.writeText(Json.encodeToString(LocalArt.serializer(), art)) }.onFailure { Log.w(TAG, "local art not saved", it) }
                rematch -= again
                Log.i(
                    TAG,
                    "scan done in ${(System.currentTimeMillis() - started) / 1000} s, ${tmdb.requests.get() - requests} TMDB requests, " +
                        "${(tvdb?.requests?.get() ?: 0) - tvdbRequests} TheTVDB requests",
                )
                _scan.value = ScanState(error = offlineMessage(nas, scanner.offline))
            } catch (e: CancellationException) {
                _scan.value = ScanState()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "scan failed", e)
                _scan.value = ScanState(error = e.toUserMessage())
            }
        }
    }

    private suspend fun save(library: Library) = dao.replace(library)

    /** "Zima 2 injoignable : ses titres sont gardés tels quels.", or null when every NAS answered. */
    private fun offlineMessage(nas: NasRouter, offline: Set<String>): String? {
        val names = offline.mapNotNull { nas.sourceOf(it)?.label }.distinct().sorted().ifEmpty { return null }
        return if (names.size == 1) {
            "${names.single()} injoignable : ses titres sont gardés tels quels."
        } else {
            "${names.joinToString(", ")} injoignables : leurs titres sont gardés tels quels."
        }
    }

    /** Only the titles stored on the shares of [nas], out of the excluded folders. */
    private fun Library.within(nas: NasRouter?): Library {
        val roots = nas?.let { router -> router.sources.flatMap(router::rootsOf) }.orEmpty().mapTo(HashSet()) { it.lowercase() }
        fun kept(file: String) = file.substringBefore('\\').lowercase() in roots && nas?.isExcluded(file) != true
        return copy(
            movies = movies.filter { kept(it.file) },
            shows = shows.mapNotNull { show ->
                val seasons = show.seasons.map { season -> season.copy(episodes = season.episodes.filter { kept(it.file) }) }.filter { it.episodes.isNotEmpty() }
                if (seasons.isEmpty()) null else show.copy(seasons = seasons)
            },
        )
    }

    private suspend fun importLegacyFile(): Library? {
        if (!legacyFile.exists()) return null
        val library = Json { ignoreUnknownKeys = true }.decodeFromString(Library.serializer(), legacyFile.readText())
        dao.replace(library)
        legacyFile.delete() // the app's own cache, now in the database
        Log.i(TAG, "imported library.json: ${library.movies.size} films, ${library.shows.size} shows")
        return library
    }

    /** The database returns rows in any order: back to title order. */
    private fun Library.sorted() = copy(
        movies = movies.sortedWith { a, b -> naturalCompare(a.title, b.title) },
        shows = shows.sortedWith { a, b -> naturalCompare(a.title, b.title) },
    )

    companion object {
        private const val TAG = "LibraryRepository"

        /** The shares scanned, to tell a library of other shares: rescanned. */
        fun keyOf(sources: List<NasSource>) =
            sources.map { "${it.protocol.label}:${it.host.trim()}/${it.shares.sorted().joinToString(",")}".lowercase() }.sorted().joinToString(";")
    }
}
