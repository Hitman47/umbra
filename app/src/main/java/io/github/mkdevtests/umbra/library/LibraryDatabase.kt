package io.github.mkdevtests.umbra.library

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.json.Json

/** [Show] without its seasons, which live in their own tables. */
@Entity(tableName = "shows")
data class ShowRow(
    @PrimaryKey val key: String,
    val tmdbId: Int?,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val genres: List<String>,
    val rating: Double?,
    val status: String?,
    val seasonEpisodes: Map<Int, Int>,
    val folders: List<String>,
    @ColumnInfo(defaultValue = "[]") val duplicates: List<String>,
    @ColumnInfo(defaultValue = "[]") val groups: List<String>,
)

@Entity(tableName = "seasons", primaryKeys = ["showKey", "number"])
data class SeasonRow(val showKey: String, val number: Int, val name: String?, val poster: String?)

/** Where the library comes from and when it was scanned: a single row. */
@Entity(tableName = "meta")
data class MetaRow(@PrimaryKey val id: Int = 0, val version: Int, val source: String, val scannedAt: Long)

/**
 * Full-text index of titles, accents ignored ("age de glace" finds "L'Âge de
 * glace"). [kind] is "movie", "show" or "episode"; [ref] is the film or episode
 * file, or the show key.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["kind", "ref"])
@Entity(tableName = "search")
data class SearchRow(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val kind: String,
    val ref: String,
    val text: String,
)

data class SearchHit(val kind: String, val ref: String)

class Converters {
    private val json = Json

    @TypeConverter
    fun fromList(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun toList(value: String): List<String> = json.decodeFromString(value)

    @TypeConverter
    fun fromCopies(value: List<FileCopy>): String = json.encodeToString(value)

    @TypeConverter
    fun toCopies(value: String): List<FileCopy> = json.decodeFromString(value)

    @TypeConverter
    fun fromCounts(value: Map<Int, Int>): String = json.encodeToString(value)

    @TypeConverter
    fun toCounts(value: String): Map<Int, Int> = json.decodeFromString(value)
}

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM meta WHERE id = 0")
    abstract suspend fun meta(): MetaRow?

    @Query("SELECT * FROM movies")
    abstract suspend fun movies(): List<Movie>

    @Query("SELECT * FROM shows")
    abstract suspend fun shows(): List<ShowRow>

    @Query("SELECT * FROM seasons")
    abstract suspend fun seasons(): List<SeasonRow>

    @Query("SELECT * FROM episodes")
    abstract suspend fun episodes(): List<Episode>

    /** Titles matching an FTS query ("dune*"). */
    @Query("SELECT kind, ref FROM search WHERE search MATCH :query LIMIT :limit")
    abstract suspend fun search(query: String, limit: Int): List<SearchHit>

    /** Replaces the whole library in one transaction: readers never see half a scan. */
    @Transaction
    open suspend fun replace(library: Library) {
        clearMovies()
        clearShows()
        clearSeasons()
        clearEpisodes()
        clearSearch()
        insertMovies(library.movies)
        insertShows(library.shows.map { it.toRow() })
        insertSeasons(library.shows.flatMap { show -> show.seasons.map { SeasonRow(show.key, it.number, it.name, it.poster) } })
        insertEpisodes(library.shows.flatMap { show -> show.seasons.flatMap { it.episodes }.map { it.copy(showKey = show.key) } })
        insertSearch(searchRows(library))
        insertMeta(MetaRow(version = library.version, source = library.source, scannedAt = library.scannedAt))
    }

    @Query("DELETE FROM movies")
    protected abstract suspend fun clearMovies()

    @Query("DELETE FROM shows")
    protected abstract suspend fun clearShows()

    @Query("DELETE FROM seasons")
    protected abstract suspend fun clearSeasons()

    @Query("DELETE FROM episodes")
    protected abstract suspend fun clearEpisodes()

    @Query("DELETE FROM search")
    protected abstract suspend fun clearSearch()

    @Insert
    protected abstract suspend fun insertMovies(rows: List<Movie>)

    @Insert
    protected abstract suspend fun insertShows(rows: List<ShowRow>)

    @Insert
    protected abstract suspend fun insertSeasons(rows: List<SeasonRow>)

    @Insert
    protected abstract suspend fun insertEpisodes(rows: List<Episode>)

    @Insert
    protected abstract suspend fun insertSearch(rows: List<SearchRow>)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertMeta(row: MetaRow)

    /** The library as the app uses it, or null before the first scan. */
    suspend fun load(): Library? {
        val meta = meta() ?: return null
        val seasons = seasons().groupBy { it.showKey }
        val episodes = episodes().groupBy { it.showKey to it.season }
        val shows = shows().map { row ->
            row.toShow(
                seasons[row.key].orEmpty().sortedBy { it.number }.map { season ->
                    Season(season.number, season.name, season.poster, episodes[row.key to season.number].orEmpty().sortedBy { it.number })
                },
            )
        }
        return Library(meta.version, meta.source, movies(), shows, meta.scannedAt)
    }

    private fun searchRows(library: Library): List<SearchRow> = buildList {
        fun text(vararg titles: String?) = titles.filterNotNull().distinct().joinToString(" ")
        library.movies.forEach { add(SearchRow(kind = "movie", ref = it.file, text = text(it.title, it.originalTitle))) }
        library.shows.forEach { add(SearchRow(kind = "show", ref = it.key, text = text(it.title, it.originalTitle))) }
        library.shows.forEach { show ->
            show.seasons.flatMap { it.episodes }.forEach { episode ->
                episode.title?.let { add(SearchRow(kind = "episode", ref = episode.file, text = it)) }
            }
        }
    }

    private fun Show.toRow() = ShowRow(
        key, tmdbId, title, originalTitle, year, overview, poster, backdrop, genres, rating, status, seasonEpisodes, folders, duplicates, groups,
    )

    private fun ShowRow.toShow(seasons: List<Season>) = Show(
        key, tmdbId, title, originalTitle, year, overview, poster, backdrop, genres, rating, status, seasonEpisodes, folders, duplicates, groups, seasons,
    )
}

@Database(
    entities = [Movie::class, ShowRow::class, SeasonRow::class, Episode::class, MetaRow::class, SearchRow::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun open(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                // Only a cache of the NAS and TMDB: a new format is rebuilt by a scan.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/** FTS query for what the user typed: every word, as a prefix ("dune par" → "dune* par*"). */
fun ftsQuery(text: String): String? =
    text.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotBlank() }.joinToString(" ") { "$it*" }.ifEmpty { null }
