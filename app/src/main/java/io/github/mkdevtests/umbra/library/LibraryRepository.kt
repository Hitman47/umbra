package io.github.mkdevtests.umbra.library

import android.util.Log
import io.github.mkdevtests.umbra.BuildConfig
import io.github.mkdevtests.umbra.UmbraApp
import io.github.mkdevtests.umbra.nas.SmbSource
import io.github.mkdevtests.umbra.nas.toUserMessage
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

/** Holds the library in memory, persists it to library.json and runs scans. */
class LibraryRepository(private val app: UmbraApp) {

    private val file = File(app.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tmdb by lazy { Tmdb(BuildConfig.TMDB_TOKEN, OkHttpClient()) }
    private var scanJob: Job? = null

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val loadJob = scope.launch {
        if (!file.exists()) return@launch
        runCatching { json.decodeFromString(Library.serializer(), file.readText()) }
            .onSuccess { _library.value = it }
            .onFailure { Log.w(TAG, "library.json unreadable, rescanning", it) }
    }

    /** Scans unless a scan of the current share already exists. */
    fun scanIfNeeded() {
        scope.launch {
            loadJob.join()
            val source = app.smb?.source ?: return@launch
            if (_library.value.source != keyOf(source) || _library.value.scannedAt == 0L) startScan()
        }
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
                val scanner = LibraryScanner(smb, tmdb) { _scan.value = ScanState(running = true, progress = it) }
                val result = scanner.scan(previous, key)
                _library.value = result
                save(result)
                _scan.value = ScanState()
            } catch (e: Exception) {
                Log.w(TAG, "scan failed", e)
                _scan.value = ScanState(error = e.toUserMessage())
            }
        }
    }

    private fun save(library: Library) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Library.serializer(), library))
        tmp.renameTo(file)
    }

    companion object {
        private const val TAG = "LibraryRepository"

        fun keyOf(source: SmbSource) = "${source.host.trim()}/${source.shares.sorted().joinToString(",")}".lowercase()
    }
}
