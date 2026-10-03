package io.github.mkdevtests.umbra.ui.perso

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mkdevtests.umbra.NyxaraApp
import io.github.mkdevtests.umbra.browse.isVideo
import io.github.mkdevtests.umbra.browse.naturalCompare
import io.github.mkdevtests.umbra.browse.sortForDisplay
import io.github.mkdevtests.umbra.browse.subtitlesFor
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.nas.toUserMessage
import io.github.mkdevtests.umbra.nas.within
import io.github.mkdevtests.umbra.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The download key of a Perso video: apart from the library's, listed in the Perso tab only. */
fun persoDownloadKey(path: String) = "perso:$path"

data class PersoState(
    /** The folder shown; "" for the list of the Perso folders. */
    val path: String = "",
    /** From the Perso folder down: "Clips › 2024". */
    val crumbs: List<String> = emptyList(),
    val entries: List<NasEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** The Perso tab: its folders, browsed as they are, played as plain videos. */
class PersoViewModel(app: Application) : AndroidViewModel(app) {
    private val nyxara = app as NyxaraApp
    private var loadJob: Job? = null
    private var listing: List<NasEntry> = emptyList()

    val store = nyxara.perso
    val progress = store.progress
    val infos = nyxara.persoMedia.infos
    val downloads = nyxara.downloads.list

    /** The Perso folders of every NAS, by name. */
    val folders: StateFlow<List<String>> = nyxara.sourceList
        .map { sources -> sources.flatMap { it.personal }.sortedWith { a, b -> naturalCompare(a.substringAfterLast('\\'), b.substringAfterLast('\\')) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sort = MutableStateFlow(PersoSort.entries.firstOrNull { it.name.lowercase() == store.sort } ?: PersoSort.Name)
    val sort: StateFlow<PersoSort> = _sort.asStateFlow()

    fun sortBy(sort: PersoSort) {
        _sort.value = sort
        store.sort = sort.name.lowercase()
    }

    private val _state = MutableStateFlow(PersoState())
    val state: StateFlow<PersoState> = _state.asStateFlow()

    fun open(path: String) {
        loadJob?.cancel()
        listing = emptyList()
        if (path.isEmpty()) {
            _state.value = PersoState()
            return
        }
        val nas = nyxara.nas ?: return
        _state.value = PersoState(path, crumbs(path), loading = true)
        loadJob = viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { nas.list(path, withPersonal = true) } }
            ensureActive()
            _state.value = result.fold(
                onSuccess = { found ->
                    listing = found
                    val shown = sortForDisplay(found)
                    nyxara.persoMedia.request(shown.filter { it.isVideo }.map { it.path to it.size })
                    _state.value.copy(entries = shown, loading = false)
                },
                onFailure = { _state.value.copy(error = it.toUserMessage(), loading = false) },
            )
        }
    }

    fun refresh() = open(_state.value.path)

    /** The parent folder, the list of the Perso folders above one; false at the top. */
    fun up(): Boolean {
        val path = _state.value.path
        if (path.isEmpty()) return false
        open(if (folders.value.any { it.equals(path, ignoreCase = true) }) "" else path.substringBeforeLast('\\'))
        return true
    }

    private fun crumbs(path: String): List<String> {
        val root = folders.value.firstOrNull { path.within(it) } ?: return listOf(path.substringAfterLast('\\'))
        return listOf(root.substringAfterLast('\\')) + path.removePrefix(root).split('\\').filter { it.isNotEmpty() }
    }

    /** Plays [folder] and everything below it, shuffled or in order; from [start] when given. */
    fun playIntent(folder: String, shuffle: Boolean, start: String? = null): Intent =
        PlayerActivity.persoIntent(getApplication(), folder, shuffle, start)

    /** Plays a downloaded video alone, NAS or not. */
    fun playDownloaded(path: String): Intent =
        PlayerActivity.persoIntent(getApplication(), path.substringBeforeLast('\\'), shuffle = false, start = path, only = true)

    fun remove(path: String, exclude: Boolean) {
        nyxara.removePersonal(path, exclude)
        if (_state.value.path.within(path)) open("")
    }

    /** The NAS of the picker. */
    val pickerSources get() = nyxara.nas?.sources.orEmpty()

    /**
     * What the folder picker shows at [place]: the NAS, then all the shares
     * the NAS offers (those the library doesn't read too), then their folders.
     */
    suspend fun pick(place: PickPlace): List<PickItem> = withContext(Dispatchers.IO) {
        val nas = nyxara.nas ?: return@withContext emptyList()
        val source = place.source ?: nas.sources.singleOrNull()
            ?: return@withContext nas.sources.map { PickItem(it.label, PickPlace(it), null) }
        val client = nas.connections.firstOrNull { it.source.id == source.id } ?: return@withContext emptyList()
        if (place.share == null) {
            val offered = runCatching { client.availableShares() }.getOrNull().orEmpty()
            val shares = (offered + source.shares).distinctBy { it.lowercase() }.sortedWith { a, b -> naturalCompare(a, b) }
            return@withContext shares.map { share ->
                val followed = source.shares.firstOrNull { it.equals(share, ignoreCase = true) }
                val tag = when {
                    followed == null -> null
                    source.isPersonal(source.rootOf(followed)) -> "Perso"
                    else -> "Bibliothèque"
                }
                PickItem(share, PickPlace(source, share), tag)
            }
        }
        val share = place.share
        val listing = runCatching { client.list(if (place.sub.isEmpty()) share else "$share\\${place.sub}") }.getOrDefault(emptyList())
        val root = source.shares.firstOrNull { it.equals(share, ignoreCase = true) }?.let(source::rootOf)
        sortForDisplay(listing).filter { it.isDirectory }.map { entry ->
            val sub = if (place.sub.isEmpty()) entry.name else "${place.sub}\\${entry.name}"
            val path = root?.let { "$it\\$sub" }
            val tag = when {
                path == null -> null
                source.isPersonal(path) -> "Perso"
                source.isExcluded(path) -> "Exclu"
                else -> null
            }
            PickItem(entry.name, PickPlace(source, share, sub), tag)
        }
    }

    /** [place] is in Perso already. */
    fun isPersonal(place: PickPlace): Boolean {
        val source = place.source ?: return false
        val share = source.shares.firstOrNull { it.equals(place.share, ignoreCase = true) } ?: return false
        val root = source.rootOf(share)
        return source.isPersonal(if (place.sub.isEmpty()) root else "$root\\${place.sub}")
    }

    fun add(place: PickPlace) {
        val source = place.source ?: return
        nyxara.addPersonal(source.id, place.share ?: return, place.sub)
    }

    /** "Zima salon · Partage Clips" for a whole share, its parent folders for a folder. */
    fun folderDetail(path: String): String {
        val source = nyxara.nas?.sourceOf(path)
        return if ('\\' in path) path.substringBeforeLast('\\').replace("\\", " › ")
        else listOfNotNull(source?.label?.takeIf { pickerSources.size > 1 }, "Partage entier").joinToString(" · ")
    }

    fun download(entry: NasEntry) {
        val subtitles = subtitlesFor(entry, listing).map { it.path }
        nyxara.downloads.add(persoDownloadKey(entry.path), entry.path, entry.name.substringBeforeLast('.'), entry.size, subtitles)
    }

    fun removeDownload(path: String) = nyxara.downloads.remove(persoDownloadKey(path))

    fun forget(path: String) = store.forget(listOf(path))
}

/** A place of the Perso folder picker: a NAS, one of its shares, a folder of it ([sub], "" for the share). */
data class PickPlace(val source: io.github.mkdevtests.umbra.nas.NasSource? = null, val share: String? = null, val sub: String = "")

/** A line of the picker; [tag]: "Bibliothèque", "Perso", "Exclu", or none (not used yet). */
data class PickItem(val label: String, val place: PickPlace, val tag: String?)

/** How the Perso videos are listed; folders stay first, by name. */
enum class PersoSort(val label: String) { Name("Nom"), Date("Récents"), Size("Taille") }

/** [entries] in [sort]'s order: folders first, then the videos. */
fun sortedFor(entries: List<NasEntry>, sort: PersoSort): List<NasEntry> {
    val (folders, videos) = entries.partition { it.isDirectory }
    return folders + when (sort) {
        PersoSort.Name -> videos
        PersoSort.Date -> videos.sortedByDescending { it.modified }
        PersoSort.Size -> videos.sortedByDescending { it.size }
    }
}
