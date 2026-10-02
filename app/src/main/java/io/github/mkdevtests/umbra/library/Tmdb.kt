package io.github.mkdevtests.umbra.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Minimal TMDB v3 client, French first. Blocking I/O runs on [Dispatchers.IO]. */
class Tmdb(private val token: String, private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun searchMovie(query: String, year: Int?): TmdbSearchItem? =
        get("search/movie", TmdbSearch.serializer(), "query" to query, "year" to year?.toString())
            .results.firstOrNull()
            ?: if (year != null) searchMovie(query, null) else null

    suspend fun searchShow(query: String, year: Int?): TmdbSearchItem? =
        get("search/tv", TmdbSearch.serializer(), "query" to query, "first_air_date_year" to year?.toString())
            .results.firstOrNull()
            ?: if (year != null) searchShow(query, null) else null

    suspend fun movie(id: Int): TmdbMovie {
        val movie = get("movie/$id", TmdbMovie.serializer())
        if (!movie.overview.isNullOrBlank()) return movie
        // Many films have no French synopsis: fall back to English.
        return movie.copy(overview = get("movie/$id", TmdbMovie.serializer(), "language" to "en-US").overview)
    }

    suspend fun show(id: Int): TmdbShow {
        val show = get("tv/$id", TmdbShow.serializer())
        if (!show.overview.isNullOrBlank()) return show
        return show.copy(overview = get("tv/$id", TmdbShow.serializer(), "language" to "en-US").overview)
    }

    suspend fun season(showId: Int, number: Int): TmdbSeason =
        get("tv/$showId/season/$number", TmdbSeason.serializer())

    private suspend fun <T> get(
        path: String,
        deserializer: DeserializationStrategy<T>,
        vararg params: Pair<String, String?>,
    ): T = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrl().newBuilder().addPathSegments(path).apply {
            if (params.none { it.first == "language" }) addQueryParameter("language", LANGUAGE)
            params.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        repeat(3) { attempt ->
            http.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    delay(1_000L * (attempt + 1)) // rate limited: back off and retry
                    return@use
                }
                if (!response.isSuccessful) throw IOException("TMDB ${response.code} on $path")
                return@withContext json.decodeFromString(deserializer, response.body!!.string())
            }
        }
        throw IOException("TMDB rate limit on $path")
    }

    companion object {
        private const val BASE_URL = "https://api.themoviedb.org/3/"
        private const val LANGUAGE = "fr-FR"

        /** Full image URL; sizes: w185/w342/w500 posters, w300 stills, w1280 backdrops. */
        fun image(path: String?, size: String): String? = path?.let { "https://image.tmdb.org/t/p/$size$it" }
    }
}

@Serializable
data class TmdbSearch(val results: List<TmdbSearchItem> = emptyList())

@Serializable
data class TmdbSearchItem(val id: Int)

@Serializable
data class TmdbGenre(val name: String)

@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
data class TmdbShow(
    val id: Int,
    val name: String,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
)

@Serializable
data class TmdbSeasonSummary(
    @SerialName("season_number") val number: Int,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
data class TmdbSeason(
    @SerialName("season_number") val number: Int,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val number: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
)
