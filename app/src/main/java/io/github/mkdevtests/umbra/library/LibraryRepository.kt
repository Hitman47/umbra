package io.github.mkdevtests.umbra.library

import android.util.Log
import io.github.mkdevtests.umbra.BuildConfig
import io.github.mkdevtests.umbra.UmbraApp
import io.github.mkdevtests.umbra.browse.naturalCompare
import io.github.mkdevtests.umbra.history.MatchFix
import io.github.mkdevtests.umbra.nas.SmbSource
import io.github.mkdevtests.umbra.nas.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

data class ScanState(val running: Boolean = false, val progress: String? = null, val error: String? = null)

/** Holds the library in memory, persists it to the database and runs scans. */
class LibraryRepository(private val app: UmbraApp) {

    private val dao by lazy { LibraryDatabase.open(app).dao() }
    /** Where earlier versions kept the library: imported once, so its TMDB matches aren't looked up again. */
    private val legacyFile = File(app.filesDir, "library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tmdb by lazy { Tmdb(BuildConfig.TMDB_TOKEN, OkHttpClient()) }
    private var scanJob: Job? = null
    /** Groups whose correction was removed, matched again by the next scan. */
    private val rematch = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val loadJob = scope.launch {
        val stored = runCatching { dao.load() ?: importLegacyFile() }
            .onFailure { Log.w(TAG, "library unreadable, rescanning", it) }
            .getOrNull() ?: return@launch
        // An older format is shown until the rescan it triggers; its TMDB matches are reused.
        _library.value = if (stored.version == Library.VERSION) stored.sorted() else stored.copy(scannedAt = 0)
    }

    /** Titles matching what the user typed: films, shows and episodes. */
    suspend fun search(text: String, limit: Int = 200): List<SearchHit> {
        loadJob.join()
        val query = ftsQuery(text) ?: return emptyList()
        return dao.search(query, limit)
    }

    /** Scans at launch if the user wants it, or when no scan of the current shares exists. */
    fun scanIfNeeded() {
        scope.launch {
            loadJob.join()
            val source = app.smb?.source ?: return@launch
            val stale = _library.value.source != keyOf(source) || _library.value.scannedAt == 0L
            if (stale || app.settings.settings.value.rescanAtLaunch) startScan()
        }
    }

    /** Shows on TMDB for what the user typed, to correct a match. */
    suspend fun searchShows(query: String): List<TmdbSearchItem> = tmdb.searchShows(query)

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

    @Synchronized
    fun startScan() {
        if (scanJob?.isActive == true) return
        val smb = app.smb ?: return
        scanJob = scope.launch {
            loadJob.join()
            _scan.value = ScanState(running = true, progress = "Lecture du NAS…")
            try {
                val key = keyOf(smb.source)
                val previous = _library.value.takeIf { it.source == key }
                    ?: Library().also { _library.value = it } // another share: don't show its titles
                val fixes = app.matchFixes.load()
                val again = synchronized(rematch) { rematch.toSet() }
                val scanner = LibraryScanner(smb, tmdb, fixes, again) { _scan.value = ScanState(running = true, progress = it) }
                val started = System.currentTimeMillis()
                val requests = tmdb.requests.get()
                val result = scanner.scan(previous, key)
                _library.value = result
                save(result)
                rematch -= again
                Log.i(TAG, "scan done in ${(System.currentTimeMillis() - started) / 1000} s, ${tmdb.requests.get() - requests} TMDB requests")
                _scan.value = ScanState()
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

        fun keyOf(source: SmbSource) = "${source.host.trim()}/${source.shares.sorted().joinToString(",")}".lowercase()
    }
}
