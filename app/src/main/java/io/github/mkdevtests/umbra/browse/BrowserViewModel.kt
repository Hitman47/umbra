package io.github.mkdevtests.umbra.browse

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mkdevtests.umbra.UmbraApp
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.nas.NasRouter
import io.github.mkdevtests.umbra.nas.SmbNas
import io.github.mkdevtests.umbra.nas.SmbSource
import io.github.mkdevtests.umbra.nas.newSourceId
import io.github.mkdevtests.umbra.nas.toUserMessage
import io.github.mkdevtests.umbra.nas.withRoots
import io.github.mkdevtests.umbra.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Path of a NAS in the browser, above its shares: ":<source id>". */
private const val SOURCE_FOLDER = ":"

data class BrowserState(
    /** Folder shown: "" lists the NAS (their shares with a single NAS), ":<id>" the shares of one, else a NAS path. */
    val path: String = "",
    /** Where [path] is, for the title: "Zima salon › Films › Drame". */
    val crumbs: List<String> = emptyList(),
    val entries: List<NasEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Result of the first setup step: logging in and listing the NAS shares. */
sealed interface ShareDiscovery {
    data class Found(val shares: List<String>) : ShareDiscovery

    /** Logged in, but the NAS won't list its shares: the user types them. */
    data object Manual : ShareDiscovery

    data class Failed(val message: String) : ShareDiscovery
}

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val umbra = app as UmbraApp
    private var loadJob: Job? = null

    /** Unfiltered listing of the current folder (subtitle files included). */
    private var listing: List<NasEntry> = emptyList()

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    val hasSource get() = umbra.nas != null
    val sources: List<SmbSource> get() = umbra.nas?.sources.orEmpty()
    val sourceList: StateFlow<List<SmbSource>> = umbra.sourceList

    init {
        if (hasSource) open("")
    }

    fun open(path: String) {
        loadJob?.cancel()
        val nas = umbra.nas ?: return
        _state.value = BrowserState(path = path, crumbs = crumbs(nas, path), loading = true)
        listing = emptyList()
        loadJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { list(nas, path) }
            }
            ensureActive() // runCatching swallowed a cancellation: a newer folder is loading
            _state.update { state ->
                result.fold(
                    onSuccess = {
                        listing = it
                        state.copy(entries = sortForDisplay(it), loading = false)
                    },
                    onFailure = { state.copy(error = it.toUserMessage(), loading = false) },
                )
            }
        }
    }

    fun refresh() = open(_state.value.path)

    /** Goes to the parent folder; false when already at the top. */
    fun up(): Boolean {
        val path = _state.value.path
        if (path.isEmpty()) return false
        val nas = umbra.nas
        open(
            when {
                '\\' in path -> path.substringBeforeLast('\\')
                path.startsWith(SOURCE_FOLDER) || nas == null || nas.sources.size < 2 -> ""
                else -> nas.sourceOf(path)?.let { SOURCE_FOLDER + it.id }.orEmpty()
            },
        )
        return true
    }

    /** With several NAS, the top lists them, then each its shares. */
    private fun list(nas: NasRouter, path: String): List<NasEntry> = when {
        path.isEmpty() && nas.sources.size > 1 -> nas.sources.map { NasEntry(it.label, SOURCE_FOLDER + it.id, isDirectory = true, size = 0) }
        path.startsWith(SOURCE_FOLDER) -> nas.sources.firstOrNull { SOURCE_FOLDER + it.id == path }
            ?.let { source -> nas.rootsOf(source).map { NasEntry(it, it, isDirectory = true, size = 0) } }
            .orEmpty()
        else -> nas.list(path)
    }

    private fun crumbs(nas: NasRouter, path: String): List<String> {
        val single = nas.sources.singleOrNull()
        return when {
            path.isEmpty() -> listOf(single?.label ?: "NAS")
            path.startsWith(SOURCE_FOLDER) -> listOfNotNull(nas.sources.firstOrNull { SOURCE_FOLDER + it.id == path }?.label)
            else -> listOfNotNull(nas.sourceOf(path)?.label) + path.split('\\')
        }
    }

    /** Intent playing [video] with the subtitle files sitting next to it. */
    fun playIntent(video: NasEntry): Intent {
        val server = umbra.streamServer
        val subtitles = subtitlesFor(video, listing).map { server.urlFor(it.path) }
        return PlayerActivity.intent(getApplication(), server.urlFor(video.path), video.name, subtitles, file = video.path)
    }

    /** Logs in to the NAS at [host] and lists its file shares. */
    suspend fun discoverShares(host: String, username: String, password: String): ShareDiscovery =
        withContext(Dispatchers.IO) {
            val nas = SmbNas(SmbSource(host, emptyList(), username, password))
            try {
                val shares = nas.availableShares()
                if (shares.isNullOrEmpty()) ShareDiscovery.Manual else ShareDiscovery.Found(shares.sortedWith(::naturalCompare))
            } catch (e: Exception) {
                ShareDiscovery.Failed(e.toUserMessage())
            } finally {
                nas.close()
            }
        }

    /**
     * Connects to [source] and, if it works, adds it to the app's sources (or
     * replaces the one it edits). Returns an error message, or null on success.
     */
    suspend fun connect(source: SmbSource): String? {
        val others = sources.filter { it.id != source.id }
        val named = source.copy(id = source.id.ifEmpty { newSourceId() }).withRoots(others)
        val connection = SmbNas(named)
        return try {
            // Every share must open: catches a typo in one of the names.
            withContext(Dispatchers.IO) {
                named.shares.forEach { share ->
                    try {
                        connection.list(share)
                    } catch (e: Exception) {
                        throw IOException("Partage « $share » : ${e.toUserMessage()}", e)
                    }
                }
            }
            umbra.saveSource(named, connection)
            open("")
            null
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { connection.close() }
            e.toUserMessage()
        }
    }

    /** Leaves out the folder at [path]: no longer scanned, browsed nor played. Shows its parent. */
    fun exclude(path: String) {
        val source = umbra.nas?.sourceOf(path) ?: return
        val updated = source.copy(excluded = (source.excluded + path).distinct())
        umbra.saveSource(updated, SmbNas(updated))
        open(path.substringBeforeLast('\\'))
    }

    /** Takes [folder] back into [source]. */
    fun include(source: SmbSource, folder: String) {
        val updated = source.copy(excluded = source.excluded - folder)
        umbra.saveSource(updated, SmbNas(updated))
        refresh()
    }

    fun remove(source: SmbSource) {
        umbra.removeSource(source.id)
        if (hasSource) open("") else _state.value = BrowserState()
    }
}
