package io.github.mkdevtests.umbra.library

import kotlinx.serialization.Serializable

/**
 * The scanned library of one NAS share, stored as JSON in the app's files.
 * NAS paths are relative to the share root and use "\".
 */
@Serializable
data class Library(
    /** "host/share" the paths belong to; a library of another share is ignored. */
    val source: String = "",
    val movies: List<Movie> = emptyList(),
    val shows: List<Show> = emptyList(),
    val scannedAt: Long = 0,
)

@Serializable
data class Movie(
    /** Movie folder, or the file itself for a video lying directly in the movies folder. */
    val folder: String,
    val file: String,
    val fileSize: Long,
    val subtitles: List<String> = emptyList(),
    val tmdbId: Int? = null,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val runtime: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
)

@Serializable
data class Show(
    val folder: String,
    val tmdbId: Int? = null,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val seasons: List<Season> = emptyList(),
)

@Serializable
data class Season(
    val number: Int,
    val name: String? = null,
    val poster: String? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class Episode(
    val season: Int,
    val number: Int,
    val file: String,
    val fileSize: Long,
    val subtitles: List<String> = emptyList(),
    val title: String? = null,
    val overview: String? = null,
    val still: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
) {
    /** True once TMDB details were merged in (a later scan won't fetch them again). */
    val hasMetadata get() = title != null
}
