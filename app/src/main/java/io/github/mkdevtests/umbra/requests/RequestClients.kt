package io.github.mkdevtests.umbra.requests

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Prowlarr's address and API key; qBittorrent's address and login. */
data class RequestSettings(
    val prowlarrUrl: String = "",
    val prowlarrKey: String = "",
    val qbitUrl: String = "",
    val qbitUser: String = "",
    val qbitPassword: String = "",
) {
    val canSearch get() = prowlarrUrl.isNotBlank() && prowlarrKey.isNotBlank()
    val canSend get() = qbitUrl.isNotBlank()
}

/**
 * Asks Prowlarr what its indexers have for a title, and hands the release
 * chosen to qBittorrent. Nothing is chosen for the user: the list is shown,
 * the user picks.
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

    /** Prowlarr's releases for [query], films or shows only. */
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
            if (!response.isSuccessful) throw IOException(if (response.code == 401) "Clé API refusée" else "Erreur ${response.code}")
        }
    }

    /** Adds [release] to qBittorrent, logging in first when needed. */
    fun send(settings: RequestSettings, release: Release) {
        val add = base(settings.qbitUrl).newBuilder().addPathSegments("api/v2/torrents/add").build()
        fun post() = qbit.newCall(Request.Builder().url(add).post(FormBody.Builder().add("urls", release.link).build()).build()).execute()
        var response = post()
        if (response.code == 403) {
            response.close()
            login(settings)
            response = post()
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful || body.trim().equals("Fails.", ignoreCase = true)) throw IOException("qBittorrent a refusé l'ajout (${it.code})")
        }
    }

    /** qBittorrent accepts the login: for Réglages' test. */
    fun login(settings: RequestSettings) {
        val url = base(settings.qbitUrl).newBuilder().addPathSegments("api/v2/auth/login").build()
        val form = FormBody.Builder().add("username", settings.qbitUser).add("password", settings.qbitPassword).build()
        // qBittorrent checks the origin of the request.
        val request = Request.Builder().url(url).post(form).header("Referer", base(settings.qbitUrl).toString()).build()
        qbit.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body?.string()?.trim() != "Ok.") throw IOException("Identifiants qBittorrent refusés")
        }
    }

    private fun base(url: String): HttpUrl = runCatching { url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }.toHttpUrl() }
        .getOrElse { throw IOException("Adresse invalide : $url") }
}
