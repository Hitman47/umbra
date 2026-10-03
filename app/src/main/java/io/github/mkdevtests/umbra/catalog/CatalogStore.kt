package io.github.mkdevtests.umbra.catalog

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.github.mkdevtests.umbra.nas.NasRouter
import io.github.mkdevtests.umbra.nas.Secrets
import io.github.mkdevtests.umbra.nas.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URLEncoder

/** When the catalogue is read again. */
enum class CatalogSync(val label: String) { Launch("À chaque lancement"), Daily("Une fois par jour"), Manual("À la main") }

data class CatalogStatus(val syncing: Boolean = false, val progress: String? = null, val error: String? = null)

/**
 * The external catalogue, kept on the device (no-backup folder) so the
 * Perso tab opens at once, offline too: its address, the last sync, each
 * profile page once opened. Read only: nothing is ever sent back.
 */
class CatalogStore(
    context: Context,
    private val client: CatalogClient,
    private val nas: () -> NasRouter?,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)
    private val file = File(context.noBackupFilesDir, "catalog.json")
    private val profiles = File(context.noBackupFilesDir, "catalog-profiles")
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    private val _address = MutableStateFlow(readAddress())
    val address: StateFlow<CatalogAddress> = _address.asStateFlow()

    private val _mode = MutableStateFlow(CatalogSync.entries.firstOrNull { it.name == prefs.getString(MODE, null) } ?: CatalogSync.Daily)
    val mode: StateFlow<CatalogSync> = _mode.asStateFlow()

    private val _index = MutableStateFlow<CatalogIndex?>(null)

    /** The last sync; null before the first one. */
    val index: StateFlow<CatalogIndex?> = _index.asStateFlow()

    private val _status = MutableStateFlow(CatalogStatus())
    val status: StateFlow<CatalogStatus> = _status.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { if (file.exists()) _index.value = CatalogIndex(json.decodeFromString(CatalogData.serializer(), file.readText())) }
                .onFailure { Log.w(TAG, "catalog unreadable", it) }
        }
    }

    private fun readAddress() = CatalogAddress(
        prefs.getString(HOME, "").orEmpty(),
        prefs.getString(AWAY, "").orEmpty(),
        prefs.getString(USER, "").orEmpty(),
        prefs.getString(PASSWORD, null)?.let(Secrets::decrypt).orEmpty(),
    )

    fun setAddress(address: CatalogAddress) {
        prefs.edit {
            putString(HOME, address.home.trim())
            putString(AWAY, address.away.trim())
            putString(USER, address.user.trim())
            putString(PASSWORD, Secrets.encrypt(address.password))
        }
        _address.value = readAddress()
        if (address.configured) sync() else forget()
    }

    fun setMode(mode: CatalogSync) {
        prefs.edit { putString(MODE, mode.name) }
        _mode.value = mode
    }

    /** At launch: a sync if the chosen rhythm calls for one. */
    fun syncIfDue() {
        if (!address.value.configured) return
        val last = prefs.getLong(LAST, 0)
        val due = when (mode.value) {
            CatalogSync.Launch -> true
            CatalogSync.Daily -> System.currentTimeMillis() - last > DAY_MS
            CatalogSync.Manual -> last == 0L
        }
        if (due) sync()
    }

    /** Reads every profile and video again; the screens keep the last sync meanwhile. */
    fun sync() {
        if (job?.isActive == true || !address.value.configured) return
        job = scope.launch(Dispatchers.IO) {
            _status.value = CatalogStatus(syncing = true, progress = "Lecture des profils…")
            try {
                val address = address.value
                val people = pages { offset -> parsePeople(read(address, "$API/performers?limit=$PAGE&offset=$offset&sort=name"), "performers") }
                val videos = pages { offset ->
                    _status.update { it.copy(progress = "Lecture des vidéos… $offset") }
                    parseVideos(read(address, "$API/library/videos?limit=$PAGE&offset=$offset&sort=path"))
                }
                val root = rootOf(videos) ?: _index.value?.data?.root
                val data = CatalogData(people, videos, System.currentTimeMillis(), root)
                file.writeText(json.encodeToString(CatalogData.serializer(), data))
                _index.value = CatalogIndex(data)
                prefs.edit { putLong(LAST, data.syncedAt) }
                _status.value = CatalogStatus(error = if (root == null) "Dossier introuvable dans Perso : ajoute le dossier racine du catalogue à Perso." else null)
            } catch (e: Exception) {
                Log.w(TAG, "sync", e)
                _status.value = CatalogStatus(error = "Synchronisation impossible : ${e.toUserMessage()}")
            }
        }
    }

    /** A profile's page: from the catalogue, else the last one read; null when neither. */
    suspend fun profile(id: String): CatalogProfile? = withContext(Dispatchers.IO) {
        val cached = File(profiles, "${id.hashCode().toUInt()}.json")
        runCatching {
            val profile = parseProfile(read(address.value, "$API/performers/${URLEncoder.encode(id, "UTF-8")}"))
            profiles.mkdirs()
            cached.writeText(json.encodeToString(CatalogProfile.serializer(), profile))
            profile
        }.getOrElse {
            runCatching { json.decodeFromString(CatalogProfile.serializer(), cached.readText()) }.getOrNull()
        }
    }

    /** The picture of a profile, for the local server ("p/<id>/<version>"). */
    fun picture(key: String): ByteArray? {
        val id = key.removePrefix("p/").substringBeforeLast('/')
        return client.bytes(address.value, "$API/performers/${URLEncoder.encode(id, "UTF-8")}/photo")
    }

    private fun forget() {
        _index.value = null
        file.delete()
        profiles.deleteRecursively()
        prefs.edit { remove(LAST) }
    }

    private fun read(address: CatalogAddress, path: String) = json.parseToJsonElement(client.text(address, path)).jsonObject

    private fun <T> pages(page: (Int) -> Pair<List<T>, Int>): List<T> {
        val all = ArrayList<T>()
        var offset = 0
        while (true) {
            val (items, total) = page(offset)
            all += items
            offset += PAGE
            if (items.isEmpty() || offset >= total) return all
        }
    }

    /**
     * The NAS folder the catalogue's root is: the Perso folder (or share)
     * where its videos are found, checked on a few of them.
     */
    private fun rootOf(videos: List<CatalogVideo>): String? {
        val router = nas() ?: return null
        val samples = videos.shuffled().take(3).ifEmpty { return null }
        val candidates = router.sources.flatMap { it.personal }
        return candidates.firstOrNull { root ->
            samples.any { video ->
                val path = "$root\\${video.path.trim('/').replace('/', '\\')}"
                runCatching { router.list(path.substringBeforeLast('\\'), withPersonal = true).any { it.path.equals(path, ignoreCase = true) } }.getOrDefault(false)
            }
        }
    }

    private companion object {
        const val TAG = "Catalog"
        const val API = "/api/v1"
        const val PAGE = 500
        const val DAY_MS = 24 * 3600_000L
        const val HOME = "home"
        const val AWAY = "away"
        const val USER = "user"
        const val PASSWORD = "password"
        const val MODE = "mode"
        const val LAST = "last"
    }
}
