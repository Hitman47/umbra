package io.github.mkdevtests.umbra.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * The scanned library of a NAS, stored in [LibraryDatabase] (library.json
 * before). NAS paths start with the share name and use "\".
 */
@Serializable
data class Library(
    /** Format of the file; an older one is rescanned from scratch. */
    val version: Int = 0,
    /** "host/shares" the paths belong to; a library of another NAS is ignored. */
    val source: String = "",
    val movies: List<Movie> = emptyList(),
    val shows: List<Show> = emptyList(),
    val scannedAt: Long = 0,
) {
    companion object {
        /**
         * 2: films found by file name anywhere in the shares, shows keyed by [Show.key].
         * 3: episodes from "Show\Saison N" folders, show and film folders for the share browser.
         */
        const val VERSION = 3
    }
}

@Serializable
@Entity(tableName = "movies")
data class Movie(
    /** Video file, also the film's identity in the library. */
    @PrimaryKey val file: String,
    val fileSize: Long,
    /** Last write time of [file]: with [fileSize], tells an unchanged file, which a rescan doesn't look up again. */
    val modified: Long = 0,
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
    /** Folder holding this film alone ("Films\Drame\Dune (2021)"), shown with its poster when browsing. */
    val folder: String? = null,
    /** Smaller copies of the film (another quality, another folder): hidden, but known to the next scan. */
    @ColumnInfo(defaultValue = "[]") val copies: List<FileCopy> = emptyList(),
)

@Serializable
data class FileCopy(val file: String, val size: Long, val modified: Long)

@Serializable
data class Show(
    /** "tmdb:1399", or "title:<normalized title>" for a show TMDB doesn't know. */
    val key: String,
    val tmdbId: Int? = null,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    /** TMDB status: "Returning Series", "Ended"… */
    val status: String? = null,
    /** Episode count of each season on TMDB, to read absolute numbers ("Show 54" = S03E17). */
    val seasonEpisodes: Map<Int, Int> = emptyMap(),
    /** Folders holding only this show, shown with its poster when browsing. */
    val folders: List<String> = emptyList(),
    /** Episode files hidden as copies of another one: known to the next scan, which doesn't look them up again. */
    val duplicates: List<String> = emptyList(),
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
@Entity(tableName = "episodes", indices = [Index("showKey")])
data class Episode(
    val season: Int,
    val number: Int,
    @PrimaryKey val file: String,
    val fileSize: Long,
    val subtitles: List<String> = emptyList(),
    val title: String? = null,
    val overview: String? = null,
    val still: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val modified: Long = 0,
    /** [Show.key] of the show, for the database. */
    val showKey: String = "",
) {
    /** True once TMDB details were merged in (a later scan won't fetch them again). */
    val hasMetadata get() = title != null
}
