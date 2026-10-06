package io.github.mkdevtests.umbra.requests

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Prowlarr's address and API key; qBittorrent's address and its API key or login. */
data class RequestSettings(
    val prowlarrUrl: String = "",
    val prowlarrKey: String = "",
    val qbitUrl: String = "",
    val qbitUser: String = "",
    val qbitPassword: String = "",
    /** qBittorrent ≥ 5.2's WebUI API key: used instead of the login when given. */
    val qbitKey: String = "",
) {
    val canSearch get() = prowlarrUrl.isNotBlank() && prowlarrKey.isNotBlank()
    val canSend get() = qbitUrl.isNotBlank()
}

/** The qBittorrent category for a film or a show (created if missing). */
fun categoryFor(isShow: Boolean) = if (isShow) "Séries Nyxara" else "Films Nyxara"

/**
 * Asks Prowlarr what its indexers have for a title, and hands the release
 * chosen to qBittorrent (or to Prowlarr's own download client). Nothing is
 * chosen for the user: the list is shown, the user picks.
 */
class RequestClients(http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    /** qBittorrent's session cookie, kept while the app runs. */
    private val cookies = object : CookieJar {
        private val saved = mutableMapOf<String, List<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            saved[url.host] = cookies
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = saved[url.host].orEmpty()
    }
    private val qbit = this.http.newBuilder().cookieJar(cookies).build()

    /** Prowlarr may answer a .torrent link with a redirection to a magnet: followed by hand. */
    private val raw = this.http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** Prowlarr's releases for [query], films or shows only, in Prowlarr's order. */
    fun search(settings: RequestSettings, query: String, isShow: Boolean): List<Release> {
        val url = base(settings.prowlarrUrl).newBuilder().addPathSegments("api/v1/search")
            .addQueryParameter("query", query)
            .addQueryParameter("type", "search")
            .addQueryParameter("categories", if (isShow) "5000" else "2000")
            .addQueryParameter("limit", "100")
            .build()
        val request = Request.Builder().url(url).get().header("X-Api-Key", settings.prowlarrKey).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw IOException("Clé API Prowlarr refusée")
            if (!response.isSuccessful) throw IOException("Prowlarr : erreur ${response.code}")
            return parseReleases(json.parseToJsonElement(response.body?.string().orEmpty()).jsonArray)
        }
    }

    /** Prowlarr answers with this key: for Réglages' test. */
    fun checkProwlarr(settings: RequestSettings) {
        val url = base(settings.prowlarrUrl).newBuilder().addPathSegments("api/v1/system/status").build()
        http.newCall(Request.Builder().url(url).get().header("X-Api-Key", settings.prowlarrKey).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException((if (response.code == 401) "Clé API refusée" else "Erreur ${response.code}") + " par $url")
        }
    }

    /**
     * Sends [release]: to qBittorrent in [category] when it is set up (the
     * magnet, or the .torrent fetched through Prowlarr), else Prowlarr hands
     * it to its own download client (usenet releases too).
     */
    fun send(settings: RequestSettings, release: Release, category: String) {
        if (!settings.canSend || release.protocol != "torrent") return grab(settings, release)
        val add = qbitUrl(settings, "torrents/add")
        val source = release.magnet?.let(TorrentSource::Magnet)
            ?: fetchTorrent(settings, release.download ?: throw IOException("Aucun lien pour cette version"), release.title)
        // Already there: qBittorrent says so, nothing to do.
        runCatching { qbitCall(settings) { Request.Builder().url(qbitUrl(settings, "torrents/createCategory")).post(FormBody.Builder().add("category", category).add("savePath", "").build()) } }
        val body = when (source) {
            is TorrentSource.Magnet -> FormBody.Builder().add("urls", source.url).add("category", category).build()
            is TorrentSource.File -> MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("torrents", source.name, source.bytes.toRequestBody("application/x-bittorrent".toMediaType()))
                .addFormDataPart("category", category)
                .build()
        }
        val answer = qbitCall(settings) { Request.Builder().url(add).post(body) }
        if (answer.trim().startsWith("Fails", ignoreCase = true)) throw IOException("qBittorrent a refusé l'ajout")
    }

    /** Prowlarr's "grab": its own download client gets the release. */
    fun grab(settings: RequestSettings, release: Release) {
        if (!settings.canSearch || release.guid.isBlank()) throw IOException("qBittorrent n'est pas réglé")
        val url = base(settings.prowlarrUrl).newBuilder().addPathSegments("api/v1/search").build()
        val payload = buildJsonObject {
            put("guid", release.guid)
            put("indexerId", release.indexerId)
        }.toString()
        val request = Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).header("X-Api-Key", settings.prowlarrKey).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Prowlarr n'a pas pu l'envoyer (${response.code})")
        }
    }

    sealed class TorrentSource {
        class File(val bytes: ByteArray, val name: String) : TorrentSource()
        class Magnet(val url: String) : TorrentSource()
    }

    /** The .torrent behind Prowlarr's [link], or the magnet it redirects to. */
    fun fetchTorrent(settings: RequestSettings, link: String, name: String): TorrentSource {
        var url = link
        repeat(4) {
            val target = url.toHttpUrlOrNull() ?: throw IOException("Lien invalide")
            val request = Request.Builder().url(target).get().apply {
                // The API key only goes to Prowlarr itself.
                if (settings.prowlarrUrl.isNotBlank() && target.host == base(settings.prowlarrUrl).host) header("X-Api-Key", settings.prowlarrKey)
            }.build()
            raw.newCall(request).execute().use { response ->
                val location = response.header("Location")
                when {
                    response.code in 300..399 && location != null ->
                        if (location.startsWith("magnet:")) return TorrentSource.Magnet(location) else url = target.resolve(location)?.toString() ?: location
                    response.isSuccessful -> {
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) throw IOException("Réponse inattendue (pas un .torrent)")
                        return TorrentSource.File(bytes, name.replace(Regex("""[\\/:*?"<>|]"""), " ").take(120) + ".torrent")
                    }
                    else -> throw IOException("Téléchargement du .torrent : erreur ${response.code}")
                }
            }
        }
        throw IOException("Trop de redirections")
    }

    /** qBittorrent answers with these settings: for Réglages' test. */
    fun checkQbit(settings: RequestSettings): String =
        qbitCall(settings) { Request.Builder().url(qbitUrl(settings, "app/version")).get() }.trim()

    /** qBittorrent's login (session cookie): only without API key. */
    fun login(settings: RequestSettings) {
        val url = qbitUrl(settings, "auth/login")
        val form = FormBody.Builder().add("username", settings.qbitUser).add("password", settings.qbitPassword).build()
        // qBittorrent checks the origin of the request.
        val request = Request.Builder().url(url).post(form).header("Referer", base(settings.qbitUrl).toString()).build()
        qbit.newCall(request).execute().use { response ->
            if (response.code == 403) throw IOException("Adresse bannie par qBittorrent (trop d'échecs)")
            if (!response.isSuccessful || response.body?.string()?.trim() != "Ok.") throw IOException("Identifiants qBittorrent refusés")
        }
    }

    /**
     * A qBittorrent call: the API key as Bearer when given, else the session
     * cookie (logging in again on 403), else nothing (qBittorrent's IP bypass).
     */
    private fun qbitCall(settings: RequestSettings, build: () -> Request.Builder): String {
        val key = settings.qbitKey.trim()
        val hasLogin = key.isEmpty() && settings.qbitUser.isNotBlank()
        fun call() = qbit.newCall(build().apply { if (key.isNotEmpty()) header("Authorization", "Bearer $key") }.build()).execute()
        var response = call()
        if (response.code == 403 && hasLogin) {
            response.close()
            login(settings)
            response = call()
        }
        response.use {
            if (it.code == 403) throw IOException(
                when {
                    key.isNotEmpty() -> "Clé API refusée (qBittorrent 5.2 ou plus récent requis)"
                    hasLogin -> "Session refusée"
                    else -> "Authentification requise : clé API ou identifiant"
                },
            )
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException("Erreur ${it.code}")
            return body
        }
    }

    private fun qbitUrl(settings: RequestSettings, path: String) = base(settings.qbitUrl).newBuilder().addPathSegments("api/v2/$path").build()

    private fun base(url: String): HttpUrl = runCatching { url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }.toHttpUrl() }
        .getOrElse { throw IOException("Adresse invalide : $url") }
}
