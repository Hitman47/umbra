package io.github.mkdevtests.umbra.download

import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

enum class DownloadState { Queued, Running, Done, Failed }

/**
 * A video copied to the device to watch offline. [key] is the history's file
 * (the film's or episode's own), [source] the NAS file read (a version).
 */
@Serializable
data class Download(
    val key: String,
    val source: String,
    val title: String,
    val size: Long,
    val local: String,
    /** The NAS subtitle files next to the video, copied with it. */
    val subtitles: List<String> = emptyList(),
    val localSubtitles: List<String> = emptyList(),
    val done: Long = 0,
    val state: DownloadState = DownloadState.Queued,
    val error: String? = null,
) {
    val fraction get() = if (size > 0) (done.toDouble() / size).toFloat().coerceIn(0f, 1f) else 0f
}

/** The downloads, kept in the preferences; the files in the app's own folder (gone with the app). */
class Downloads(private val context: Context) {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Download>> = _list.asStateFlow()

    /** Where the copies go: the app's folder on the device's storage. */
    val folder: File get() = (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir.resolve("movies")).apply { mkdirs() }

    /** Queues [source] (NAS path) as the offline copy of [key]; the service starts it. */
    fun add(key: String, source: String, title: String, size: Long, subtitles: List<String>) {
        if (_list.value.any { it.key == key && it.state != DownloadState.Failed }) return
        val name = safeName(source.substringAfterLast('\\'))
        val download = Download(key, source, title, size, folder.resolve(name).absolutePath, subtitles)
        update { list -> list.filter { it.key != key } + download }
        DownloadService.start(context)
    }

    /** Deletes the copy (and stops it if under way). */
    fun remove(key: String) {
        val gone = _list.value.filter { it.key == key }
        update { list -> list.filter { it.key != key } }
        gone.forEach { download ->
            File(download.local).delete()
            File(download.local + PART).delete()
            download.localSubtitles.forEach { File(it).delete() }
        }
    }

    /** Failed ones queued again. */
    fun retry(key: String) {
        update { list -> list.map { if (it.key == key && it.state == DownloadState.Failed) it.copy(state = DownloadState.Queued, error = null) else it } }
        DownloadService.start(context)
    }

    /** The offline copy of [key], when finished and still there. */
    fun localFile(key: String): Download? = _list.value.firstOrNull { it.key == key && it.state == DownloadState.Done && File(it.local).exists() }

    fun next(): Download? = _list.value.firstOrNull { it.state == DownloadState.Queued || it.state == DownloadState.Running }

    internal fun update(change: (List<Download>) -> List<Download>) {
        _list.update(change)
        prefs.edit { putString(KEY, json.encodeToString(ListSerializer(Download.serializer()), _list.value)) }
    }

    internal fun set(download: Download) = update { list -> list.map { if (it.key == download.key) download else it } }

    private fun load(): List<Download> = runCatching {
        json.decodeFromString(ListSerializer(Download.serializer()), prefs.getString(KEY, null) ?: return emptyList())
    }.getOrDefault(emptyList())
        // Cut off by the app's end: picked up again where it stopped.
        .map { if (it.state == DownloadState.Running) it.copy(state = DownloadState.Queued) else it }

    companion object {
        const val PART = ".part"
        private const val KEY = "list"

        /** A file name safe on any storage. */
        fun safeName(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }
}
