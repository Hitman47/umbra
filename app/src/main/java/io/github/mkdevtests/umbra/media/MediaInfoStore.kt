package io.github.mkdevtests.umbra.media

import android.util.Log
import io.github.mkdevtests.umbra.nas.NasRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What each video file holds (quality, languages, subtitles), read from its
 * header on the NAS when a page shows it, then kept in [file]: a file is read
 * again only if its size changed.
 */
class MediaInfoStore(private val file: File, private val nas: () -> NasRouter?, private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }
    private val _infos = MutableStateFlow(load())
    val infos: StateFlow<Map<String, MediaInfo>> = _infos.asStateFlow()

    private val pending = ConcurrentHashMap.newKeySet<String>()
    /** Files that couldn't be read in this session (unknown format, NAS away): not asked again. */
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val reading = Semaphore(PARALLEL)
    private var saving: Job? = null

    /** Reads the files ([path] to its size) not known yet. */
    fun request(files: List<Pair<String, Long>>) {
        files.filter { (path, size) -> _infos.value[path]?.size != size && path !in failed && pending.add(path) }.forEach { (path, _) ->
            scope.launch(Dispatchers.IO) {
                try {
                    reading.withPermit {
                        val info = runCatching { nas()?.open(path)?.use { readMediaInfo(it, path) } }
                            .onFailure { Log.w(TAG, "media info of $path", it) }
                            .getOrNull()
                        if (info == null) {
                            failed += path
                        } else {
                            _infos.update { it + (path to info) }
                            saveSoon()
                        }
                    }
                } finally {
                    pending -= path
                }
            }
        }
    }

    /** What mpv found playing [path], kept when the header couldn't be read. */
    fun remember(path: String, info: MediaInfo) {
        if (_infos.value[path] == info) return
        _infos.update { it + (path to info) }
        saveSoon()
    }

    @Synchronized
    private fun saveSoon() {
        saving?.cancel()
        saving = scope.launch(Dispatchers.IO) {
            delay(2_000)
            runCatching {
                val temp = File(file.path + ".tmp")
                temp.writeText(json.encodeToString(_infos.value))
                temp.renameTo(file)
            }.onFailure { Log.w(TAG, "save", it) }
        }
    }

    private fun load(): Map<String, MediaInfo> = runCatching {
        if (file.exists()) json.decodeFromString<Map<String, MediaInfo>>(file.readText()) else emptyMap()
    }.getOrElse { emptyMap() }

    private companion object {
        const val TAG = "MediaInfoStore"
        const val PARALLEL = 2
    }
}
