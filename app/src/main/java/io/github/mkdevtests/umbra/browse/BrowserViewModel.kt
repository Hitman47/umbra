package io.github.mkdevtests.umbra.browse

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mkdevtests.umbra.UmbraApp
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.nas.SmbNas
import io.github.mkdevtests.umbra.nas.SmbSource
import io.github.mkdevtests.umbra.nas.toUserMessage
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

data class BrowserState(
    /** Folder shown, relative to the share root ("" = root). */
    val path: String = "",
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

    val hasSource get() = umbra.smb != null
    val source get() = umbra.smb?.source

    init {
        if (hasSource) open("")
    }

    fun open(path: String) {
        loadJob?.cancel()
        _state.value = BrowserState(path = path, loading = true)
        listing = emptyList()
        loadJob = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { umbra.smb!!.list(path) }
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

    /** Goes to the parent folder; false when already at the share root. */
    fun up(): Boolean {
        val path = _state.value.path
        if (path.isEmpty()) return false
        open(path.substringBeforeLast('\\', ""))
        return true
    }

    /** Intent playing [video] with the subtitle files sitting next to it. */
    fun playIntent(video: NasEntry): Intent {
        val server = umbra.streamServer
        val subtitles = subtitlesFor(video, listing).map { server.urlFor(it.path) }
        return PlayerActivity.intent(getApplication(), server.urlFor(video.path), video.name, subtitles)
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
     * Connects to [source] and, if it works, makes it the app's source.
     * Returns an error message, or null on success.
     */
    suspend fun connect(source: SmbSource): String? {
        val connection = SmbNas(source)
        return try {
            // Every share must open: catches a typo in one of the names.
            withContext(Dispatchers.IO) {
                source.shares.forEach { share ->
                    try {
                        connection.list(share)
                    } catch (e: Exception) {
                        throw IOException("Partage « $share » : ${e.toUserMessage()}", e)
                    }
                }
            }
            umbra.useSource(source, connection)
            open("")
            null
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { connection.close() }
            e.toUserMessage()
        }
    }
}
