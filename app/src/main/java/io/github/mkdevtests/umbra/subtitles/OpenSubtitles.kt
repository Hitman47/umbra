package io.github.mkdevtests.umbra.subtitles

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.github.mkdevtests.umbra.nas.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/** A subtitle found online; [hashMatch]: made for this very file (same release, same timing). */
data class OnlineSubtitle(
    val fileId: Int,
    val language: String,
    val release: String,
    val downloads: Int,
    val hashMatch: Boolean,
    val hearingImpaired: Boolean,
    val machineTranslated: Boolean,
    /** Made for the same release as the file (group, or source and resolution): likely in sync. */
    val sameRelease: Boolean = false,
)

/** What is searched: the file's hash and size, and TMDB's ids when the title is known. */
data class SubtitleQuery(
    val hash: String?,
    val fileName: String,
    val movieTmdb: Int? = null,
    val showTmdb: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class OpenSubtitlesStatus(
    val configured: Boolean,
    val user: String? = null,
    /** Downloads a day for the account. */
    val allowed: Int? = null,
    /** Downloads left today, once one was made. */
    val remaining: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Subtitles from opensubtitles.com (REST API v1), only searched and
 * downloaded: nothing is ever uploaded. The account is optional (more
 * downloads a day); its password is encrypted with the Keystore.
 */
class OpenSubtitles(context: Context, private val apiKey: String, private val userAgent: String, private val http: OkHttpClient) {

    private val prefs = context.getSharedPreferences("opensubtitles", Context.MODE_PRIVATE)
    /** Kept with the app's files: a subtitle chosen once comes back with its video. */
    private val folder = context.filesDir.resolve("subtitles")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(
        OpenSubtitlesStatus(configured = apiKey.isNotBlank(), user = prefs.getString(KEY_USER, null), allowed = prefs.getInt(KEY_ALLOWED, -1).takeIf { it >= 0 }),
    )
    val status: StateFlow<OpenSubtitlesStatus> = _status.asStateFlow()

    private var token: String? = prefs.getString(KEY_TOKEN, null)
    private var baseUrl: String = prefs.getString(KEY_BASE, null) ?: DEFAULT_BASE

    fun login(user: String, password: String) {
        _status.update { it.copy(busy = true, error = null) }
        scope.launch {
            runCatching { signIn(user, password) }
                .onSuccess {
                    prefs.edit { putString(KEY_USER, user).putString(KEY_PASSWORD, Secrets.encrypt(password)) }
                    _status.update { it.copy(user = user, busy = false) }
                }
                .onFailure { e -> _status.update { it.copy(busy = false, error = "Connexion refusée : ${e.message}") } }
        }
    }

    fun logout() {
        token = null
        baseUrl = DEFAULT_BASE
        prefs.edit { clear() }
        _status.update { OpenSubtitlesStatus(configured = it.configured) }
    }

    private fun signIn(user: String, password: String) {
        val body = json.encodeToString(Login.serializer(), Login(user, password))
        val result = json.decodeFromString(LoginResult.serializer(), call("$DEFAULT_BASE/login", body))
        token = result.token
        baseUrl = result.baseUrl?.let { "https://$it/api/v1" } ?: DEFAULT_BASE
        prefs.edit {
            putString(KEY_TOKEN, token).putString(KEY_BASE, baseUrl)
            result.user?.allowedDownloads?.let { putInt(KEY_ALLOWED, it) }
        }
        _status.update { it.copy(allowed = result.user?.allowedDownloads) }
    }

    /**
     * Subtitles in [languages] (ISO 639-1, in the order of preference) for
     * [query]: those made for this file first, then the most downloaded.
     */
    suspend fun search(query: SubtitleQuery, languages: List<String>): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        val langs = languages.joinToString(",") { it.lowercase() }
        coroutineScope {
            val byHash = query.hash?.let { hash -> async { runCatching { find(listOf("languages" to langs, "moviehash" to hash)) }.getOrDefault(emptyList()) } }
            val byTitle = async {
                val params = when {
                    query.showTmdb != null && query.season != null && query.episode != null -> listOf(
                        "episode_number" to "${query.episode}", "languages" to langs, "parent_tmdb_id" to "${query.showTmdb}", "season_number" to "${query.season}",
                    )
                    query.movieTmdb != null -> listOf("languages" to langs, "tmdb_id" to "${query.movieTmdb}")
                    else -> listOf("languages" to langs, "query" to query.fileName.substringBeforeLast('.').lowercase())
                }
                find(params)
            }
            val hashed = byHash?.await().orEmpty().filter { it.hashMatch }
            val titled = byTitle.await()
            val file = releaseTraits(query.fileName)
            (hashed + titled).distinctBy { it.fileId }
                .map { it.copy(sameRelease = it.hashMatch || sameRelease(file, releaseTraits(it.release))) }
                .sortedWith(
                    compareByDescending<OnlineSubtitle> { it.hashMatch }
                        .thenByDescending { it.sameRelease }
                        .thenBy { it.machineTranslated }
                        .thenByDescending { it.downloads },
                )
        }
    }

    /** Downloads [subtitle] into the app's cache; the player adds the file. */
    suspend fun download(subtitle: OnlineSubtitle): File = withContext(Dispatchers.IO) {
        val target = folder.resolve("${subtitle.fileId}.srt")
        if (target.exists() && target.length() > 0) return@withContext target
        val result = withSession { json.decodeFromString(DownloadResult.serializer(), call("$baseUrl/download", """{"file_id":${subtitle.fileId}}""")) }
        _status.update { it.copy(remaining = result.remaining) }
        val link = result.link ?: throw IOException(result.message ?: "Téléchargement refusé")
        http.newCall(Request.Builder().url(link).header("User-Agent", userAgent).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            folder.mkdirs()
            target.writeBytes(response.body!!.bytes())
        }
        target
    }

    private fun find(params: List<Pair<String, String>>): List<OnlineSubtitle> {
        // The API wants its parameters sorted and lowercase, or it redirects.
        val url = "$baseUrl/subtitles".toHttpUrl().newBuilder().apply { params.sortedBy { it.first }.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val result = json.decodeFromString(SearchResult.serializer(), withSession { call(url.toString(), null) })
        return result.data.mapNotNull { item ->
            val attributes = item.attributes
            val file = attributes.files.firstOrNull() ?: return@mapNotNull null
            OnlineSubtitle(
                fileId = file.fileId,
                language = attributes.language.orEmpty(),
                release = attributes.release?.ifBlank { null } ?: file.fileName.orEmpty(),
                downloads = attributes.downloadCount ?: 0,
                hashMatch = attributes.moviehashMatch == true,
                hearingImpaired = attributes.hearingImpaired == true,
                machineTranslated = attributes.machineTranslated == true || attributes.aiTranslated == true,
            )
        }
    }

    /** Runs [block]; an expired session is opened again once with the saved account. */
    private fun <T> withSession(block: () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val user = prefs.getString(KEY_USER, null)
        val password = prefs.getString(KEY_PASSWORD, null)?.let(Secrets::decrypt)
        if (e.code != 401 || user == null || password == null) throw e
        signIn(user, password)
        block()
    }

    private class HttpException(val code: Int, message: String) : IOException(message)

    private fun call(url: String, body: String?): String {
        val request = Request.Builder().url(url)
            .header("Api-Key", apiKey)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .apply { if (body != null) post(body.toRequestBody("application/json".toMediaType())) }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "${request.method} ${url.substringBefore('?')}: ${response.code}")
                val message = runCatching { json.decodeFromString(DownloadResult.serializer(), text).message }.getOrNull()
                throw HttpException(response.code, message ?: "HTTP ${response.code}")
            }
            return text
        }
    }

    @Serializable private data class Login(val username: String, val password: String)

    @Serializable private data class LoginUser(@SerialName("allowed_downloads") val allowedDownloads: Int? = null)

    @Serializable private data class LoginResult(val token: String, val user: LoginUser? = null, @SerialName("base_url") val baseUrl: String? = null)

    @Serializable private data class SubtitleFile(@SerialName("file_id") val fileId: Int, @SerialName("file_name") val fileName: String? = null)

    @Serializable private data class Attributes(
        val language: String? = null,
        val release: String? = null,
        @SerialName("download_count") val downloadCount: Int? = null,
        @SerialName("moviehash_match") val moviehashMatch: Boolean? = null,
        @SerialName("hearing_impaired") val hearingImpaired: Boolean? = null,
        @SerialName("machine_translated") val machineTranslated: Boolean? = null,
        @SerialName("ai_translated") val aiTranslated: Boolean? = null,
        val files: List<SubtitleFile> = emptyList(),
    )

    @Serializable private data class Item(val attributes: Attributes)

    @Serializable private data class SearchResult(val data: List<Item> = emptyList())

    @Serializable private data class DownloadResult(val link: String? = null, val remaining: Int? = null, val message: String? = null)

    private companion object {
        const val TAG = "OpenSubtitles"
        const val DEFAULT_BASE = "https://api.opensubtitles.com/api/v1"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "token"
        const val KEY_BASE = "base_url"
        const val KEY_ALLOWED = "allowed"
    }
}
