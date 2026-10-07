package io.github.mkdevtests.umbra.history

import android.content.Context
import androidx.room.AutoMigration
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How far a video was played, keyed by its NAS path (the library's identity of a film or episode). */
@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val file: String,
    /** Seconds. */
    val position: Double,
    val duration: Double,
    val updatedAt: Long,
) {
    /** Played into the credits: the last minute, or the last 8 % of a long film. */
    val watched get() = duration > 0 && duration - position <= maxOf(60.0, duration * 0.08)

    /** Worth resuming: past the first minute and not finished. */
    val inProgress get() = position >= 60 && !watched

    val remaining get() = (duration - position).coerceAtLeast(0.0)

    val fraction get() = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress")
    suspend fun all(): List<Progress>

    @Upsert
    suspend fun save(progress: Progress)
}

/**
 * What the user did, apart from the library: the library is a cache rebuilt
 * by a scan, the history and the match corrections must survive any change.
 * Never migrated destructively.
 */
@Database(
    entities = [Progress::class, MatchFix::class, HiddenTitle::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): ProgressDao

    abstract fun matchFixes(): MatchFixDao

    abstract fun hidden(): HiddenTitleDao

    companion object {
        fun open(context: Context, name: String = "history.db"): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
    }
}

/** What was watched and where playback stopped, kept in memory for the screens. */
class WatchHistory(private val dao: ProgressDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    init {
        scope.launch {
            runCatching { dao.all() }
                .onSuccess { rows -> _progress.update { saved -> rows.associateBy { it.file } + saved } }
                .onFailure { Log.w(TAG, "history unreadable", it) }
        }
    }

    /**
     * Marks [files] (path → duration in seconds, 0 if unknown) watched or not,
     * as the user asks from a poster or a page. "Not watched" is a fresh
     * position 0: newer than what Trakt says, it wins on the screens.
     */
    fun mark(files: Map<String, Double>, watched: Boolean) {
        val now = System.currentTimeMillis()
        val entries = files.map { (file, known) ->
            val duration = _progress.value[file]?.duration?.takeIf { it > 0 } ?: known.takeIf { it > 0 } ?: 1.0
            Progress(file, if (watched) duration else 0.0, duration, now)
        }
        _progress.update { it + entries.associateBy { entry -> entry.file } }
        scope.launch { runCatching { entries.forEach { dao.save(it) } }.onFailure { Log.w(TAG, "marks not saved", it) } }
    }

    /** Called by the player while it plays [file]; ignored until mpv knows the duration. */
    /** Entries from a backup, newer than this device's ones. */
    fun restore(entries: List<Progress>) {
        if (entries.isEmpty()) return
        _progress.update { saved -> saved + entries.associateBy { it.file } }
        scope.launch { runCatching { entries.forEach { dao.save(it) } }.onFailure { Log.w(TAG, "restore not saved", it) } }
    }

    fun save(file: String, position: Double, duration: Double) {
        if (duration <= 0) return
        val entry = Progress(file, position.coerceIn(0.0, duration), duration, System.currentTimeMillis())
        _progress.update { it + (file to entry) }
        scope.launch { runCatching { dao.save(entry) }.onFailure { Log.w(TAG, "history not saved", it) } }
    }

    private companion object {
        const val TAG = "WatchHistory"
    }
}
