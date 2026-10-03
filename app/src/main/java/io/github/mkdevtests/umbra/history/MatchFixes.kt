package io.github.mkdevtests.umbra.history

import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The show the user chose for a group of episode files (a show folder, or
 * loose files with one title), applied by every scan instead of the TMDB search.
 */
@Entity(tableName = "match_fixes")
data class MatchFix(
    /** The scanner's group: "folder:Animes\Monogatari\Bakemonogatari" or "title:claymore". */
    @PrimaryKey val groupKey: String,
    /** TMDB show, or null for a show of its own, named by its folder, without TMDB. */
    val tmdbId: Int?,
    /** Season the files belong to, whatever their names say; null keeps theirs. */
    val season: Int? = null,
    /** TMDB number of the group's first episode in [season] (arcs sharing one TMDB season). */
    val firstEpisode: Int = 1,
)

@Dao
interface MatchFixDao {
    @Query("SELECT * FROM match_fixes")
    suspend fun all(): List<MatchFix>

    @Upsert
    suspend fun save(fixes: List<MatchFix>)

    @Query("DELETE FROM match_fixes WHERE groupKey IN (:groups)")
    suspend fun remove(groups: List<String>)
}

/** Match corrections, kept in memory for the scanner and the screens. */
class MatchFixes(private val dao: MatchFixDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _fixes = MutableStateFlow<Map<String, MatchFix>>(emptyMap())
    val fixes: StateFlow<Map<String, MatchFix>> = _fixes.asStateFlow()

    private val loadJob: Job = scope.launch {
        runCatching { dao.all() }
            .onSuccess { rows -> _fixes.update { rows.associateBy { it.groupKey } + it } }
            .onFailure { Log.w(TAG, "match fixes unreadable", it) }
    }

    /** All corrections, once read from the database (the scanner must not start without them). */
    suspend fun load(): Map<String, MatchFix> {
        loadJob.join()
        return _fixes.value
    }

    suspend fun save(fixes: List<MatchFix>) {
        _fixes.update { it + fixes.associateBy { fix -> fix.groupKey } }
        dao.save(fixes)
    }

    suspend fun remove(groups: List<String>) {
        _fixes.update { it - groups.toSet() }
        dao.remove(groups)
    }

    private companion object {
        const val TAG = "MatchFixes"
    }
}
