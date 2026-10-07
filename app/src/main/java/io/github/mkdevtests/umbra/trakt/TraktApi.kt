package io.github.mkdevtests.umbra.trakt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import android.util.Log
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Everything Nyxara may ask Trakt, and nothing else. Nyxara must never lose the
 * user's Trakt history: no endpoint here removes, resets or rewrites anything
 * (no history removal, no playback deletion, no revoke). The only writes are
 * the scrobbles of what is played, which add a play past 80 % and otherwise
 * save a resume point, as every media center does. TraktSafetyTest fails the
 * build if this list changes.
 */
enum class TraktEndpoint(val method: String, val path: String) {
    DeviceCode("POST", "oauth/device/code"),
    DeviceToken("POST", "oauth/device/token"),
    RefreshToken("POST", "oauth/token"),
    LastActivities("GET", "sync/last_activities"),
    WatchedMovies("GET", "sync/watched/movies"),
    WatchedShows("GET", "sync/watched/shows"),
    PlaybackMovies("GET", "sync/playback/movies"),
    PlaybackEpisodes("GET", "sync/playback/episodes"),
    WatchlistMovies("GET", "sync/watchlist/movies"),
    WatchlistShows("GET", "sync/watchlist/shows"),
    ShowByTmdb("GET", "search/tmdb/{id}"),
    Episode("GET", "shows/{id}/seasons/{season}/episodes/{episode}"),
    ShowProgress("GET", "shows/{id}/progress/watched"),
    ScrobbleStart("POST", "scrobble/start"),
    ScrobblePause("POST", "scrobble/pause"),
    ScrobbleStop("POST", "scrobble/stop"),
}

/** HTTP status the caller handles itself (the device code polling). */
class TraktHttpException(val code: Int, message: String) : IOException(message)

/** Minimal Trakt client. Blocking I/O runs on [Dispatchers.IO]; tokens are never logged. */
class TraktApi(
    private val clientId: String,
    private val clientSecret: String,
    private val userAgent: String,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val configured get() = clientId.isNotBlank()

    suspend fun deviceCode(): TraktDeviceCode =
        call(TraktEndpoint.DeviceCode, body = json.encodeToString(TraktClient.serializer(), TraktClient(clientId)), result = TraktDeviceCode.serializer())

    /** Throws [TraktHttpException] 400 while the user hasn't entered the code yet. */
    suspend fun deviceToken(code: String): TraktToken = call(
        TraktEndpoint.DeviceToken,
        body = json.encodeToString(TraktDeviceTokenRequest.serializer(), TraktDeviceTokenRequest(code, clientId, clientSecret.ifBlank { null })),
        result = TraktToken.serializer(),
    )

    suspend fun refresh(refreshToken: String): TraktToken = call(
        TraktEndpoint.RefreshToken,
        body = json.encodeToString(
            TraktRefreshRequest.serializer(),
            TraktRefreshRequest(refreshToken, clientId, clientSecret.ifBlank { null }),
        ),
        result = TraktToken.serializer(),
    )

    suspend fun lastActivities(token: String): TraktLastActivities =
        call(TraktEndpoint.LastActivities, token = token, result = TraktLastActivities.serializer())

    suspend fun watchedMovies(token: String): List<TraktWatchedMovie> = all(TraktEndpoint.WatchedMovies, token, TraktWatchedMovie.serializer())

    suspend fun watchedShows(token: String): List<TraktWatchedShow> = all(TraktEndpoint.WatchedShows, token, TraktWatchedShow.serializer())

    /** What the user watched of one show, season by season, by episode numbers. */
    suspend fun showProgress(token: String, showTraktId: Int): TraktShowProgress = call(
        TraktEndpoint.ShowProgress,
        token = token,
        params = mapOf("id" to showTraktId.toString()),
        result = TraktShowProgress.serializer(),
    )

    suspend fun playbackMovies(token: String): List<TraktPlayback> = all(TraktEndpoint.PlaybackMovies, token, TraktPlayback.serializer())

    suspend fun playbackEpisodes(token: String): List<TraktPlayback> = all(TraktEndpoint.PlaybackEpisodes, token, TraktPlayback.serializer())

    /** The titles put aside to watch ("À voir"), read only. */
    suspend fun watchlist(token: String): List<TraktListed> =
        all(TraktEndpoint.WatchlistMovies, token, TraktListed.serializer()) + all(TraktEndpoint.WatchlistShows, token, TraktListed.serializer())

    /**
     * Every page of a list: without paging parameters Trakt may answer with
     * only a first page, so pages are asked for until X-Pagination-Page-Count.
     */
    private suspend fun <T> all(endpoint: TraktEndpoint, token: String, item: KSerializer<T>): List<T> {
        val items = ArrayList<T>()
        var page = 1
        do {
            var pages = 1
            items += call(
                endpoint, token = token, query = mapOf("page" to page.toString(), "limit" to PAGE_SIZE.toString()),
                result = ListSerializer(item),
            ) { headers ->
                pages = headers["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
                if (page == 1) Log.i("Trakt", "${endpoint.name}: ${headers.names().filter { it.startsWith("X-Pagination", true) }.joinToString { "$it=${headers[it]}" }}")
            }
            page++
        } while (page <= pages && page <= MAX_PAGES)
        return items
    }

    /** Trakt id of the show TMDB knows as [tmdbId], null if Trakt doesn't have it. */
    suspend fun showByTmdb(tmdbId: Int): Int? = call(
        TraktEndpoint.ShowByTmdb,
        params = mapOf("id" to tmdbId.toString()),
        query = mapOf("type" to "show"),
        result = ListSerializer(TraktSearchResult.serializer()),
    ).firstNotNullOfOrNull { it.show?.ids?.trakt }

    suspend fun episode(showTraktId: Int, season: Int, number: Int): TraktEpisode = call(
        TraktEndpoint.Episode,
        params = mapOf("id" to showTraktId.toString(), "season" to season.toString(), "episode" to number.toString()),
        result = TraktEpisode.serializer(),
    )

    /** [endpoint] is one of the scrobbles; [body] says what is played and how far. */
    suspend fun scrobble(endpoint: TraktEndpoint, token: String, body: TraktScrobble): JsonObject {
        require(endpoint in SCROBBLES)
        return call(endpoint, token = token, body = json.encodeToString(TraktScrobble.serializer(), body), result = JsonObject.serializer())
    }

    private suspend fun <T> call(
        endpoint: TraktEndpoint,
        token: String? = null,
        params: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        body: String? = null,
        result: KSerializer<T>?,
        onHeaders: (Headers) -> Unit = {},
    ): T = withContext(Dispatchers.IO) {
        check(configured) { "Trakt client id missing" }
        val path = params.entries.fold(endpoint.path) { path, (key, value) -> path.replace("{$key}", value) }
        val url = okhttp3.HttpUrl.Builder().scheme("https").host(HOST).addPathSegments(path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("trakt-api-key", clientId)
            .header("trakt-api-version", "2")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .apply {
                when (endpoint.method) {
                    "GET" -> get()
                    "POST" -> post((body ?: "{}").toRequestBody(JSON))
                    else -> error("Nyxara never sends ${endpoint.method} to Trakt")
                }
            }
            .build()

        repeat(3) { attempt ->
            http.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    delay(2_000L * (attempt + 1)) // rate limited: back off and retry
                    return@use
                }
                if (!response.isSuccessful) throw TraktHttpException(response.code, "Trakt ${response.code} on ${endpoint.name}")
                onHeaders(response.headers)
                @Suppress("UNCHECKED_CAST")
                return@withContext if (result == null) Unit as T else json.decodeFromString(result, response.body!!.string())
            }
        }
        throw TraktHttpException(429, "Trakt rate limit on ${endpoint.name}")
    }

    private companion object {
        const val HOST = "api.trakt.tv"
        const val PAGE_SIZE = 250
        const val MAX_PAGES = 200
        val JSON = "application/json".toMediaType()
        val SCROBBLES = setOf(TraktEndpoint.ScrobbleStart, TraktEndpoint.ScrobblePause, TraktEndpoint.ScrobbleStop)
    }
}

@Serializable
private data class TraktClient(@SerialName("client_id") val clientId: String)

@Serializable
private data class TraktDeviceTokenRequest(
    val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String?,
)

@Serializable
private data class TraktRefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String?,
    @SerialName("redirect_uri") val redirectUri: String = "urn:ietf:wg:oauth:2.0:oob",
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
data class TraktDeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

@Serializable
data class TraktToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class TraktIds(val trakt: Int? = null, val tmdb: Int? = null, val tvdb: Int? = null)

@Serializable
data class TraktMedia(val title: String? = null, val year: Int? = null, val ids: TraktIds = TraktIds())

@Serializable
data class TraktEpisode(val season: Int = 0, val number: Int = 0, val title: String? = null, val ids: TraktIds = TraktIds())

@Serializable
data class TraktWatchedMovie(
    @SerialName("last_watched_at") val lastWatchedAt: String,
    val movie: TraktMedia,
)

@Serializable
data class TraktWatchedShow(
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("reset_at") val resetAt: String? = null,
    val show: TraktMedia,
    val seasons: List<TraktWatchedSeason> = emptyList(),
)

@Serializable
data class TraktWatchedSeason(val number: Int, val episodes: List<TraktWatchedEpisode> = emptyList())

@Serializable
data class TraktWatchedEpisode(val number: Int, @SerialName("last_watched_at") val lastWatchedAt: String)

@Serializable
data class TraktShowProgress(
    @SerialName("reset_at") val resetAt: String? = null,
    val seasons: List<TraktProgressSeason> = emptyList(),
)

@Serializable
data class TraktProgressSeason(val number: Int, val episodes: List<TraktProgressEpisode> = emptyList())

@Serializable
data class TraktProgressEpisode(
    val number: Int,
    val completed: Boolean = false,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
)

@Serializable
data class TraktPlayback(
    val progress: Double,
    @SerialName("paused_at") val pausedAt: String,
    val movie: TraktMedia? = null,
    val show: TraktMedia? = null,
    val episode: TraktEpisode? = null,
)

@Serializable
private data class TraktSearchResult(val show: TraktMedia? = null)

/** Timestamps of the user's last changes: nothing to download when they didn't move. */
@Serializable
data class TraktLastActivities(
    val movies: TraktActivity = TraktActivity(),
    val episodes: TraktActivity = TraktActivity(),
    val shows: TraktActivity = TraktActivity(),
)

@Serializable
data class TraktActivity(
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("hidden_at") val hiddenAt: String? = null,
    @SerialName("watchlisted_at") val watchlistedAt: String? = null,
)

/** A title of the watchlist. */
@Serializable
data class TraktListed(
    @SerialName("listed_at") val listedAt: String? = null,
    val movie: TraktMedia? = null,
    val show: TraktMedia? = null,
)

/** A film by its TMDB id, or an episode by its Trakt id. */
@Serializable
data class TraktScrobble(
    val progress: Double,
    val movie: TraktScrobbleItem? = null,
    val episode: TraktScrobbleItem? = null,
)

@Serializable
data class TraktScrobbleItem(val ids: TraktIds)
