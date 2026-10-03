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
import kotlin.math.abs
import kotlin.math.ln

/** Minimal TMDB v3 client, French first. Blocking I/O runs on [Dispatchers.IO]. */
class Tmdb(private val token: String, private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /** HTTP requests sent so far, for the scan report. */
    val requests = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Best TMDB film for the first of [queries] that matches well, else the
     * best candidate overall. Results are ranked by title equality and year,
     * not just taken in TMDB's order.
     */
    suspend fun findMovie(queries: List<String>, year: Int?): Int? =
        find(queries, year) { query -> search("search/movie", "year", query, year) }

    suspend fun findShow(queries: List<String>, year: Int?): Int? =
        find(queries, year) { query -> search("search/tv", "first_air_date_year", query, year) }

    /** Shows for what the user typed, in TMDB's order, to pick the right one by hand. */
    suspend fun searchShows(query: String): List<TmdbSearchItem> =
        get("search/tv", TmdbSearch.serializer(), "query" to query).results

    /** TMDB film for an IMDb id ("tt0055928"), the most reliable match when the file name has one. */
    suspend fun movieForImdb(imdbId: String): Int? =
        get("find/$imdbId", TmdbFind.serializer(), "external_source" to "imdb_id").movieResults.firstOrNull()?.id

    private suspend fun find(queries: List<String>, year: Int?, search: suspend (String) -> List<TmdbSearchItem>): Int? {
        var best: Pair<TmdbSearchItem, Double>? = null
        for (query in queries.filter { it.isNotBlank() }.distinct()) {
            val candidate = search(query).withIndex().maxByOrNull { (index, item) -> score(item, index, query, year) } ?: continue
            val score = score(candidate.value, candidate.index, query, year)
            if (best == null || score > best.second) best = candidate.value to score
            if (score >= GOOD_MATCH) break // same title: no need to try the other queries
        }
        return best?.first?.id
    }

    /** Year-filtered search first; without the year if that finds nothing (wrong or missing year). */
    private suspend fun search(path: String, yearParam: String, query: String, year: Int?): List<TmdbSearchItem> {
        if (year != null) {
            get(path, TmdbSearch.serializer(), "query" to query, yearParam to year.toString()).results
                .takeIf { it.isNotEmpty() }?.let { return it }
        }
        return get(path, TmdbSearch.serializer(), "query" to query).results
    }

    suspend fun movie(id: Int): TmdbMovie {
        val movie = get("movie/$id", TmdbMovie.serializer(), "append_to_response" to "credits")
        if (!movie.overview.isNullOrBlank()) return movie
        // Many films have no French synopsis: fall back to English.
        return movie.copy(overview = get("movie/$id", TmdbMovie.serializer(), "language" to "en-US").overview)
    }

    suspend fun show(id: Int): TmdbShow {
        val show = get("tv/$id", TmdbShow.serializer(), "append_to_response" to "credits")
        if (!show.overview.isNullOrBlank()) return show
        return show.copy(overview = get("tv/$id", TmdbShow.serializer(), "language" to "en-US").overview)
    }

    /** Cast and crew with photos, recommendations and saga of a film, for its page. */
    suspend fun movieExtras(id: Int): TmdbMovieExtras =
        get("movie/$id", TmdbMovieExtras.serializer(), "append_to_response" to "credits,recommendations")

    /** Films of a saga ("Dune Collection"). */
    suspend fun collection(id: Int): TmdbCollection = get("collection/$id", TmdbCollection.serializer())

    /** Cast of every season, creators and recommendations of a show, for its page. */
    suspend fun showExtras(id: Int): TmdbShowExtras =
        get("tv/$id", TmdbShowExtras.serializer(), "append_to_response" to "aggregate_credits,recommendations")

    /** TheTVDB id of a show, null if TMDB doesn't know it. */
    suspend fun tvdbId(showId: Int): Int? =
        get("tv/$showId/external_ids", TmdbExternalIds.serializer()).tvdbId?.takeIf { it > 0 }

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
            requests.incrementAndGet()
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
        private const val EXACT_TITLE = 100.0
        private const val GOOD_MATCH = 80.0

        /** Higher is better: same title (French or original), same year, high in TMDB's ranking. */
        private fun score(item: TmdbSearchItem, index: Int, query: String, year: Int?): Double {
            val wanted = normalizeTitle(query)
            val titles = listOfNotNull(item.title, item.originalTitle, item.name, item.originalName).map(::normalizeTitle)
            var score = -3.0 * index + ln(1.0 + (item.popularity ?: 0.0))
            score += when {
                wanted in titles -> EXACT_TITLE
                titles.any { it.isNotEmpty() && (it.startsWith(wanted) || wanted.startsWith(it)) } -> 30.0
                else -> 0.0
            }
            val released = (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()
            if (year != null && released != null) {
                score += when (abs(released - year)) {
                    0 -> 50.0
                    1 -> 30.0
                    else -> -20.0
                }
            }
            return score
        }

        private const val BASE_URL = "https://api.themoviedb.org/3/"
        private const val LANGUAGE = "fr-FR"

        /** Full image URL; sizes: w185/w342/w500 posters, w300 stills, w1280 backdrops. */
        fun image(path: String?, size: String): String? = path?.let { "https://image.tmdb.org/t/p/$size$it" }
    }
}

@Serializable
data class TmdbSearch(val results: List<TmdbSearchItem> = emptyList())

@Serializable
data class TmdbSearchItem(
    val id: Int,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
data class TmdbFind(@SerialName("movie_results") val movieResults: List<TmdbSearchItem> = emptyList())

/** Someone in a cast or crew; [roles] and [jobs] are a show's, over all its seasons. */
@Serializable
data class TmdbCredit(
    val name: String,
    val character: String? = null,
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val roles: List<TmdbRole> = emptyList(),
    val jobs: List<TmdbJob> = emptyList(),
    @SerialName("total_episode_count") val episodes: Int? = null,
)

@Serializable
data class TmdbRole(val character: String? = null)

@Serializable
data class TmdbJob(val job: String? = null)

@Serializable
data class TmdbFullCredits(val cast: List<TmdbCredit> = emptyList(), val crew: List<TmdbCredit> = emptyList())

@Serializable
data class TmdbCollectionRef(val id: Int, val name: String? = null)

@Serializable
data class TmdbCollection(val id: Int, val name: String? = null, val parts: List<TmdbSearchItem> = emptyList())

@Serializable
data class TmdbMovieExtras(
    @SerialName("belongs_to_collection") val collection: TmdbCollectionRef? = null,
    val credits: TmdbFullCredits? = null,
    val recommendations: TmdbSearch? = null,
)

@Serializable
data class TmdbShowExtras(
    @SerialName("created_by") val createdBy: List<TmdbCredit> = emptyList(),
    @SerialName("aggregate_credits") val credits: TmdbFullCredits? = null,
    val recommendations: TmdbSearch? = null,
)

@Serializable
data class TmdbExternalIds(@SerialName("tvdb_id") val tvdbId: Int? = null)

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
    val credits: TmdbCredits? = null,
)

@Serializable
data class TmdbCredits(val cast: List<TmdbPerson> = emptyList(), val crew: List<TmdbPerson> = emptyList()) {
    /** Billing order: the leads first. */
    fun actors(count: Int = 10) = cast.map { it.name }.distinct().take(count)
}

@Serializable
data class TmdbPerson(val name: String, val job: String? = null)

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
    val status: String? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    @SerialName("created_by") val createdBy: List<TmdbPerson> = emptyList(),
    val credits: TmdbCredits? = null,
)

@Serializable
data class TmdbSeasonSummary(
    @SerialName("season_number") val number: Int,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
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
