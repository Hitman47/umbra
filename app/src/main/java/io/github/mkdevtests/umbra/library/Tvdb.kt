package io.github.mkdevtests.umbra.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal TheTVDB v4 client. Only the episode lists are used: TheTVDB knows
 * the absolute number of each anime episode, which TMDB doesn't.
 */
class Tvdb(private val apiKey: String, private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }
    private val loginLock = Mutex()

    /** Bearer token, valid a month: one login per app run. */
    @Volatile private var token: String? = null

    /** HTTP requests sent so far, for the scan report. */
    val requests = AtomicInteger()

    /** Every episode of a series in its aired order, specials included. */
    suspend fun episodes(seriesId: Int): List<TvdbEpisode> {
        val all = mutableListOf<TvdbEpisode>()
        for (page in 0 until MAX_PAGES) {
            val body = get("series/$seriesId/episodes/official", TvdbEpisodePage.serializer(), "page" to page.toString())
            all += body.data.episodes
            if (body.links?.next == null || body.data.episodes.isEmpty()) break
        }
        return all
    }

    private suspend fun <T> get(path: String, deserializer: DeserializationStrategy<T>, vararg params: Pair<String, String>): T {
        val url = BASE_URL.toHttpUrl().newBuilder().addPathSegments(path).apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        repeat(2) { attempt ->
            val bearer = token ?: login()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $bearer").header("Accept", "application/json").build()
            val body = withContext(Dispatchers.IO) {
                requests.incrementAndGet()
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 && attempt == 0 -> null // token expired: log in again
                        !response.isSuccessful -> throw IOException("TheTVDB ${response.code} on $path")
                        else -> response.body!!.string()
                    }
                }
            }
            if (body != null) return json.decodeFromString(deserializer, body)
            token = null
        }
        throw IOException("TheTVDB refuses the key")
    }

    private suspend fun login(): String = loginLock.withLock {
        token?.let { return it }
        val request = Request.Builder()
            .url("${BASE_URL}login")
            .post("""{"apikey":${json.encodeToString(apiKey)}}""".toRequestBody("application/json".toMediaType()))
            .build()
        val body = withContext(Dispatchers.IO) {
            requests.incrementAndGet()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("TheTVDB login ${response.code}")
                response.body!!.string()
            }
        }
        json.decodeFromString(TvdbLogin.serializer(), body).data.token.also { token = it }
    }

    private companion object {
        const val BASE_URL = "https://api4.thetvdb.com/v4/"

        /** 500 episodes a page: enough for One Piece. */
        const val MAX_PAGES = 10
    }
}

@Serializable
data class TvdbLogin(val data: Data) {
    @Serializable
    data class Data(val token: String)
}

@Serializable
data class TvdbEpisodePage(val data: Data, val links: TvdbLinks? = null) {
    @Serializable
    data class Data(val episodes: List<TvdbEpisode> = emptyList())
}

@Serializable
data class TvdbLinks(val next: String? = null)

/** An episode in TheTVDB's aired order; [absoluteNumber] is 0 or null when unknown. */
@Serializable
data class TvdbEpisode(
    val seasonNumber: Int? = null,
    val number: Int? = null,
    val absoluteNumber: Int? = null,
    /** "2019-04-07". */
    val aired: String? = null,
)
