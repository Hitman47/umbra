package io.github.mkdevtests.umbra.history

import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Show
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A title the user doesn't want to see in the lists, by its [hideKey]. */
@Entity(tableName = "hidden")
data class HiddenTitle(@PrimaryKey val key: String)

@Dao
interface HiddenTitleDao {
    @Query("SELECT * FROM hidden")
    suspend fun all(): List<HiddenTitle>

    @Upsert
    suspend fun save(title: HiddenTitle)

    @Query("DELETE FROM hidden WHERE `key` = :key")
    suspend fun remove(key: String)
}

/** The film's TMDB id, which survives a move or a new copy of the file; the file without one. */
val Movie.hideKey get() = tmdbId?.let { "movie:$it" } ?: "file:$file"

val Show.hideKey get() = key

/** The library without the titles the user hid. */
fun Library.without(hidden: Set<String>): Library =
    if (hidden.isEmpty()) this else copy(movies = movies.filter { it.hideKey !in hidden }, shows = shows.filter { it.hideKey !in hidden })

/** Hidden titles, kept in memory for the screens. */
class HiddenTitles(private val dao: HiddenTitleDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    init {
        scope.launch {
            runCatching { dao.all() }
                .onSuccess { rows -> _keys.update { it + rows.map { row -> row.key } } }
                .onFailure { Log.w(TAG, "hidden titles unreadable", it) }
        }
    }

    fun setHidden(key: String, hidden: Boolean) {
        _keys.update { if (hidden) it + key else it - key }
        scope.launch {
            runCatching { if (hidden) dao.save(HiddenTitle(key)) else dao.remove(key) }
                .onFailure { Log.w(TAG, "hidden title not saved", it) }
        }
    }

    private companion object {
        const val TAG = "HiddenTitles"
    }
}
