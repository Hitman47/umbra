package io.github.mkdevtests.umbra.requests

import android.content.Context
import androidx.core.content.edit
import io.github.mkdevtests.umbra.nas.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient


/** The requests' settings (keys encrypted) and the titles asked for lately. */
class RequestStore(context: Context, http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("requests", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val clients = RequestClients(http)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<RequestSettings> = _settings.asStateFlow()

    private val _requested = MutableStateFlow(readRequested())
    val requested: StateFlow<List<Requested>> = _requested.asStateFlow()

    private fun read() = RequestSettings(
        prefs.getString(PROWLARR_URL, "").orEmpty(),
        prefs.getString(PROWLARR_KEY, null)?.let(Secrets::decrypt).orEmpty(),
        prefs.getString(QBIT_URL, "").orEmpty(),
        prefs.getString(QBIT_USER, "").orEmpty(),
        prefs.getString(QBIT_PASSWORD, null)?.let(Secrets::decrypt).orEmpty(),
    )

    fun save(settings: RequestSettings) {
        prefs.edit {
            putString(PROWLARR_URL, settings.prowlarrUrl.trim())
            putString(PROWLARR_KEY, Secrets.encrypt(settings.prowlarrKey.trim()))
            putString(QBIT_URL, settings.qbitUrl.trim())
            putString(QBIT_USER, settings.qbitUser.trim())
            putString(QBIT_PASSWORD, Secrets.encrypt(settings.qbitPassword))
        }
        _settings.value = read()
    }

    suspend fun search(query: String, isShow: Boolean): List<Release> = withContext(Dispatchers.IO) { clients.search(settings.value, query, isShow) }

    /** Sends [release] to qBittorrent and notes the title as asked for. */
    suspend fun send(release: Release, tmdbId: Int, isShow: Boolean, title: String) {
        withContext(Dispatchers.IO) { clients.send(settings.value, release) }
        update { list -> list.filterNot { it.tmdbId == tmdbId && it.isShow == isShow } + Requested(tmdbId, isShow, title, release.title, System.currentTimeMillis()) }
    }

    /** A test of both: null when they answer, else what is wrong. */
    suspend fun check(settings: RequestSettings): String = withContext(Dispatchers.IO) {
        val prowlarr = if (!settings.canSearch) "Prowlarr : non réglé" else runCatching { clients.checkProwlarr(settings); "Prowlarr : OK" }.getOrElse { "Prowlarr : ${it.message}" }
        val qbit = if (!settings.canSend) "qBittorrent : non réglé" else runCatching { clients.login(settings); "qBittorrent : OK" }.getOrElse { "qBittorrent : ${it.message}" }
        "$prowlarr · $qbit"
    }

    /** Titles now in the library ([owned]) or asked for long ago leave the list. */
    fun prune(owned: (Int, Boolean) -> Boolean) = update { list -> pruned(list, owned, System.currentTimeMillis()) }

    private fun update(change: (List<Requested>) -> List<Requested>) {
        _requested.value = change(_requested.value)
        prefs.edit { putString(REQUESTED, json.encodeToString(ListSerializer(Requested.serializer()), _requested.value)) }
    }

    private fun readRequested(): List<Requested> = runCatching {
        json.decodeFromString(ListSerializer(Requested.serializer()), prefs.getString(REQUESTED, null) ?: "[]")
    }.getOrDefault(emptyList())

    companion object {
        private const val PROWLARR_URL = "prowlarr_url"
        private const val PROWLARR_KEY = "prowlarr_key"
        private const val QBIT_URL = "qbit_url"
        private const val QBIT_USER = "qbit_user"
        private const val QBIT_PASSWORD = "qbit_password"
        private const val REQUESTED = "requested"
    }
}
