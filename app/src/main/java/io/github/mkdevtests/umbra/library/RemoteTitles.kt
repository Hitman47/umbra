package io.github.mkdevtests.umbra.library

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A film or a show not in the library, as TMDB describes it: the page of a similar title. */
@Serializable
data class RemoteTitle(
    val tmdbId: Int,
    val isShow: Boolean,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val runtime: Int? = null,
    val seasons: Int? = null,
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val fetchedAt: Long = 0,
) {
    val key get() = (if (isShow) "t:" else "m:") + tmdbId
}

/**
 * The pages of absent titles opened lately, kept on the device a week (the
 * [MAX] most recent): opened again at once, even offline; forgotten after.
 */
class RemoteTitles(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RemoteTitle.serializer())
    private var titles: MutableMap<String, RemoteTitle> = load()

    @Synchronized
    fun get(tmdbId: Int, isShow: Boolean): RemoteTitle? = titles[(if (isShow) "t:" else "m:") + tmdbId]?.takeIf { fresh(it) }

    @Synchronized
    fun put(title: RemoteTitle) {
        titles[title.key] = title.copy(fetchedAt = now())
        titles = titles.values.filter(::fresh).sortedByDescending { it.fetchedAt }.take(MAX).associateByTo(LinkedHashMap()) { it.key }
        runCatching { file.writeText(json.encodeToString(serializer, titles.values.toList())) }.onFailure { Log.w(TAG, "save", it) }
    }

    private fun fresh(title: RemoteTitle) = now() - title.fetchedAt < MAX_AGE_MS

    private fun load(): MutableMap<String, RemoteTitle> = runCatching {
        json.decodeFromString(serializer, file.readText()).filter(::fresh).associateByTo(LinkedHashMap()) { it.key }
    }.getOrDefault(LinkedHashMap())

    companion object {
        private const val TAG = "RemoteTitles"
        const val MAX = 200
        const val MAX_AGE_MS = 7 * 24 * 3600_000L
    }
}

fun TmdbMovie.toRemote() = RemoteTitle(
    tmdbId = id, isShow = false, title = title, originalTitle = originalTitle, year = releaseDate?.take(4)?.toIntOrNull(),
    overview = overview, poster = posterPath, backdrop = backdropPath, genres = genres.map { it.name }, rating = voteAverage?.takeIf { it > 0 },
    runtime = runtime?.takeIf { it > 0 },
    directors = credits?.crew.orEmpty().filter { it.job == "Director" }.map { it.name }.distinct(),
    cast = credits?.cast.orEmpty().take(10).map { it.name },
)

fun TmdbShow.toRemote() = RemoteTitle(
    tmdbId = id, isShow = true, title = name, originalTitle = originalName, year = firstAirDate?.take(4)?.toIntOrNull(),
    overview = overview, poster = posterPath, backdrop = backdropPath, genres = genres.map { it.name }, rating = voteAverage?.takeIf { it > 0 },
    seasons = seasons.count { it.number > 0 }.takeIf { it > 0 },
    directors = createdBy.map { it.name }.distinct(),
    cast = credits?.cast.orEmpty().take(10).map { it.name },
)
